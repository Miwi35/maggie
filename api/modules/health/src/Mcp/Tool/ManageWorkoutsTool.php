<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Exception\InvalidWorkoutException;
use Maggie\Health\Mcp\WorkoutJson;
use Maggie\Health\Message\CreateWorkoutCommand;
use Maggie\Health\Message\DeleteWorkoutCommand;
use Maggie\Health\Message\UpdateWorkoutCommand;
use Maggie\Health\Repository\WorkoutRepository;
use Maggie\Health\Service\WorkoutRules;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Never completes a request by deduction: a missing field is named, a partial
 * update is refused with `current`, the workout as it is, so the next call can
 * be whole.
 */
#[McpTool(name: 'manage_workouts', description: 'List, create, update or delete the user\'s workouts (running, walking, cycling, swimming, strength, climbing, other). action: list, create, update or delete. A workout has type (run, walk, bike, swim, strength, climbing, other), title, startedAt (ISO 8601 WITH time and offset, e.g. 2026-10-20T07:10:00+02:00 — never a date alone, ask the user for the hour), durationMinutes (whole minutes, 1 to 1440), distanceMeters (whole METRES, optional: 5.4 km is 5400), kcal (optional, 0 to 20000), avgHeartRate (optional, bpm, 20 to 250), feeling (optional: very_good, good, hard, very_hard), notes (optional), source (manual, watch, activity, estimated, planned; defaults to manual on create — planned is a session still to do) and externalId (optional, unique per user, de-duplicates an import). list returns the most recent first and filters on type, source, after and before (ISO 8601 with offset, on startedAt), limit default 20, max 100. update and delete need workoutId; on update only the fields provided change (nothing is cleared, an optional field is edited in the admin). create needs type, title, startedAt and durationMinutes — nothing is deduced: ask the user for what is missing. Returns {workouts: [...]} for list and {success, workout} otherwise, each workout with typeLabel, feelingLabel and sourceLabel in French. A refused call changes nothing and answers {error, field?, current?}: current is the workout as it is now.')]
final class ManageWorkoutsTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly MessageBusInterface $bus,
        private readonly WorkoutRepository $workoutRepository,
        private readonly WorkoutRules $rules,
    ) {
    }

    public function __invoke(
        #[Schema(enum: ['list', 'create', 'update', 'delete'])]
        string $action,
        ?string $workoutId = null,
        #[Schema(enum: ['run', 'walk', 'bike', 'swim', 'strength', 'climbing', 'other'])]
        ?string $type = null,
        ?string $title = null,
        ?string $startedAt = null,
        ?int $durationMinutes = null,
        ?int $distanceMeters = null,
        ?int $kcal = null,
        ?int $avgHeartRate = null,
        #[Schema(enum: ['very_good', 'good', 'hard', 'very_hard'])]
        ?string $feeling = null,
        ?string $notes = null,
        #[Schema(enum: ['manual', 'watch', 'activity', 'estimated', 'planned'])]
        ?string $source = null,
        ?string $externalId = null,
        ?string $after = null,
        ?string $before = null,
        ?int $limit = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list($type, $source, $after, $before, $limit),
                'create' => $this->create($type, $title, $startedAt, $durationMinutes, $distanceMeters, $kcal, $avgHeartRate, $feeling, $notes, $source, $externalId),
                'update' => $this->update($workoutId, $type, $title, $startedAt, $durationMinutes, $distanceMeters, $kcal, $avgHeartRate, $feeling, $notes, $source, $externalId),
                'delete' => $this->delete($workoutId),
                default => self::json(['error' => "Unknown action: {$action}. Use list, create, update or delete."]),
            };
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        } catch (InvalidWorkoutException $e) {
            return self::json(['error' => $e->getMessage(), 'field' => $e->field]);
        }
    }

    private function list(?string $type, ?string $source, ?string $after, ?string $before, ?int $limit): string
    {
        $user = $this->userContext->requireUser();

        $workouts = $this->workoutRepository->findByUser(
            $user,
            null === $type ? null : $this->rules->type($type),
            null === $source ? null : $this->rules->source($source),
            null === $after ? null : $this->filterDate('after', $after),
            null === $before ? null : $this->filterDate('before', $before),
            max(1, min($limit ?? 20, 100)),
        );

        return self::json(['workouts' => WorkoutJson::list($workouts)]);
    }

    private function create(?string $type, ?string $title, ?string $startedAt, ?int $durationMinutes, ?int $distanceMeters, ?int $kcal, ?int $avgHeartRate, ?string $feeling, ?string $notes, ?string $source, ?string $externalId): string
    {
        $user = $this->userContext->requireUser();

        foreach (['type' => $type, 'title' => $title, 'startedAt' => $startedAt, 'durationMinutes' => $durationMinutes] as $field => $value) {
            if (null === $value) {
                return self::json(['error' => "{$field} is required. Ask the user, never assume.", 'field' => $field]);
            }
        }
        \assert(null !== $type && null !== $title && null !== $startedAt && null !== $durationMinutes);

        try {
            $workout = $this->bus->dispatch(new CreateWorkoutCommand(
                userId: (string) $user->getId(),
                type: $type,
                title: $title,
                startedAt: $startedAt,
                durationMinutes: $durationMinutes,
                distanceMeters: $distanceMeters,
                kcal: $kcal,
                avgHeartRate: $avgHeartRate,
                feeling: $feeling,
                notes: $notes,
                source: $source ?? 'manual',
                externalId: $externalId,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            return self::refusal($e);
        }

        return self::json(['success' => true, 'workout' => WorkoutJson::of($workout)]);
    }

    private function update(?string $workoutId, ?string $type, ?string $title, ?string $startedAt, ?int $durationMinutes, ?int $distanceMeters, ?int $kcal, ?int $avgHeartRate, ?string $feeling, ?string $notes, ?string $source, ?string $externalId): string
    {
        if (null === $workoutId) {
            return self::json(['error' => 'workoutId is required for update.', 'field' => 'workoutId']);
        }

        $user = $this->userContext->requireUser();
        $before = $this->workoutRepository->findOneOfUser($user, $workoutId);
        if (null === $before) {
            return self::json(['error' => "Workout not found: {$workoutId}"]);
        }
        // Read before the handler touches the entity: the state it is refused against.
        $current = WorkoutJson::of($before);

        $given = [$type, $title, $startedAt, $durationMinutes, $distanceMeters, $kcal, $avgHeartRate, $feeling, $notes, $source, $externalId];
        if ([] === array_filter($given, static fn (mixed $value): bool => null !== $value)) {
            return self::json(['error' => 'Nothing to change: give at least one field to update.', 'current' => $current]);
        }

        try {
            $workout = $this->bus->dispatch(new UpdateWorkoutCommand(
                userId: (string) $user->getId(),
                workoutId: $workoutId,
                type: $type,
                title: $title,
                startedAt: $startedAt,
                durationMinutes: $durationMinutes,
                distanceMeters: $distanceMeters,
                kcal: $kcal,
                avgHeartRate: $avgHeartRate,
                feeling: $feeling,
                notes: $notes,
                source: $source,
                externalId: $externalId,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            return self::refusal($e, $current);
        }

        return self::json(['success' => true, 'workout' => WorkoutJson::of($workout)]);
    }

    private function delete(?string $workoutId): string
    {
        if (null === $workoutId) {
            return self::json(['error' => 'workoutId is required for delete.', 'field' => 'workoutId']);
        }

        try {
            $this->bus->dispatch(new DeleteWorkoutCommand(
                userId: (string) $this->userContext->requireUser()->getId(),
                workoutId: $workoutId,
            ));
        } catch (HandlerFailedException $e) {
            return self::refusal($e);
        }

        return self::json(['success' => true]);
    }

    private function filterDate(string $field, string $value): \DateTimeImmutable
    {
        try {
            return $this->rules->startedAt($value);
        } catch (InvalidWorkoutException) {
            throw new InvalidWorkoutException($field, 'must be ISO 8601 with time and offset, e.g. 2026-10-20T00:00:00+02:00.');
        }
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
        if ($cause instanceof InvalidWorkoutException && null !== $cause->field) {
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
