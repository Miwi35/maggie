<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Exception\InvalidBodyMeasurementException;
use Maggie\Health\Mcp\BodyMeasurementJson;
use Maggie\Health\Message\CreateBodyMeasurementCommand;
use Maggie\Health\Message\DeleteBodyMeasurementCommand;
use Maggie\Health\Message\UpdateBodyMeasurementCommand;
use Maggie\Health\Repository\BodyMeasurementRepository;
use Maggie\Health\Service\BodyMeasurementRules;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;
use Psr\Clock\ClockInterface;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The user's weighings (MAG-706). « Je pèse 72,4 kg » is a create with
 * weightKg 72.4 and nothing else: the moment is now, the source manual. A
 * partial update is refused with `current`, the measurement as it is.
 */
#[McpTool(name: 'manage_body_measurements', description: 'A measurement is one weighing or body measure at an instant. Actions: list, create, update, delete, exclude. Fields: measuredAt (ISO 8601 with offset, default now), weightKg (max 500), bodyFatPercent (<100), waistCm (max 300), all >0, at least one on create; source (manual default, health_connect, withings), externalId, excluded (kept, not counted). update, delete, exclude need measurementId; update changes only fields given. list: after, before, limit (max 366). Returns {measurements} or {success, measurement}. Refusal changes nothing: {error, field?, current?}.')]
final class ManageBodyMeasurementsTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly MessageBusInterface $bus,
        private readonly BodyMeasurementRepository $measurementRepository,
        private readonly BodyMeasurementRules $rules,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(
        #[Schema(enum: ['list', 'create', 'update', 'delete', 'exclude'])]
        string $action,
        ?string $measurementId = null,
        ?string $measuredAt = null,
        ?float $weightKg = null,
        ?float $bodyFatPercent = null,
        ?float $waistCm = null,
        #[Schema(enum: ['manual', 'health_connect', 'withings'])]
        ?string $source = null,
        ?string $externalId = null,
        ?bool $excluded = null,
        ?string $after = null,
        ?string $before = null,
        ?int $limit = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list($after, $before, $limit),
                'create' => $this->create($measuredAt, $weightKg, $bodyFatPercent, $waistCm, $source, $externalId),
                'update' => $this->update($measurementId, $measuredAt, $weightKg, $bodyFatPercent, $waistCm, $source, $externalId, $excluded),
                'exclude' => $this->exclude($measurementId),
                'delete' => $this->delete($measurementId),
                default => self::json(['error' => "Unknown action: {$action}. Use list, create, update, delete or exclude."]),
            };
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        } catch (InvalidBodyMeasurementException $e) {
            return self::json(['error' => $e->getMessage(), 'field' => $e->field]);
        }
    }

    private function list(?string $after, ?string $before, ?int $limit): string
    {
        $user = $this->userContext->requireUser();

        $measurements = $this->measurementRepository->findByUser(
            $user,
            null === $after ? null : $this->rules->measuredAt($after, 'after'),
            null === $before ? null : $this->rules->measuredAt($before, 'before'),
            max(1, min($limit ?? 30, 366)),
        );

        return self::json(['measurements' => BodyMeasurementJson::list($measurements)]);
    }

    private function create(?string $measuredAt, ?float $weightKg, ?float $bodyFatPercent, ?float $waistCm, ?string $source, ?string $externalId): string
    {
        $user = $this->userContext->requireUser();

        try {
            $measurement = $this->bus->dispatch(new CreateBodyMeasurementCommand(
                userId: (string) $user->getId(),
                measuredAt: $measuredAt ?? $this->clock->now()->format(\DATE_ATOM),
                weightKg: $weightKg,
                bodyFatPercent: $bodyFatPercent,
                waistCm: $waistCm,
                source: $source ?? 'manual',
                externalId: $externalId,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            return self::refusal($e);
        }

        return self::json(['success' => true, 'measurement' => BodyMeasurementJson::of($measurement)]);
    }

    private function update(?string $measurementId, ?string $measuredAt, ?float $weightKg, ?float $bodyFatPercent, ?float $waistCm, ?string $source, ?string $externalId, ?bool $excluded): string
    {
        if (null === $measurementId) {
            return self::json(['error' => 'measurementId is required for update.', 'field' => 'measurementId']);
        }

        $user = $this->userContext->requireUser();
        $before = $this->measurementRepository->findOneOfUser($user, $measurementId);
        if (null === $before) {
            return self::json(['error' => "Measurement not found: {$measurementId}"]);
        }
        // Read before the handler touches the entity: the state it is refused against.
        $current = BodyMeasurementJson::of($before);

        $given = [$measuredAt, $weightKg, $bodyFatPercent, $waistCm, $source, $externalId, $excluded];
        if ([] === array_filter($given, static fn (mixed $value): bool => null !== $value)) {
            return self::json(['error' => 'Nothing to change: give at least one field to update.', 'current' => $current]);
        }

        try {
            $measurement = $this->bus->dispatch(new UpdateBodyMeasurementCommand(
                userId: (string) $user->getId(),
                measurementId: $measurementId,
                measuredAt: $measuredAt,
                weightKg: $weightKg,
                bodyFatPercent: $bodyFatPercent,
                waistCm: $waistCm,
                source: $source,
                externalId: $externalId,
                excluded: $excluded,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            return self::refusal($e, $current);
        }

        return self::json(['success' => true, 'measurement' => BodyMeasurementJson::of($measurement)]);
    }

    /** « Écarter »: the update that sets the measurement aside, nothing else. */
    private function exclude(?string $measurementId): string
    {
        if (null === $measurementId) {
            return self::json(['error' => 'measurementId is required for exclude.', 'field' => 'measurementId']);
        }

        return $this->update($measurementId, null, null, null, null, null, null, true);
    }

    private function delete(?string $measurementId): string
    {
        if (null === $measurementId) {
            return self::json(['error' => 'measurementId is required for delete.', 'field' => 'measurementId']);
        }

        try {
            $this->bus->dispatch(new DeleteBodyMeasurementCommand(
                userId: (string) $this->userContext->requireUser()->getId(),
                measurementId: $measurementId,
            ));
        } catch (HandlerFailedException $e) {
            return self::refusal($e);
        }

        return self::json(['success' => true]);
    }

    /**
     * A domain refusal is Maggie's to read; anything else is ours.
     *
     * @param array<string, mixed>|null $current
     */
    private static function refusal(HandlerFailedException $e, ?array $current = null): string
    {
        $cause = $e->getPrevious() ?? $e;
        if (!$cause instanceof \DomainException) {
            throw $e;
        }

        $data = ['error' => $cause->getMessage()];
        if ($cause instanceof InvalidBodyMeasurementException && null !== $cause->field) {
            $data['field'] = $cause->field;
        }
        if (null !== $current) {
            $data['current'] = $current;
        }

        return self::json($data);
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, \JSON_THROW_ON_ERROR);
    }
}
