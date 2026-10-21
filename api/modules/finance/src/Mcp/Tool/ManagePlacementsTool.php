<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Message\CreatePlacementCommand;
use Maggie\Finance\Message\DeletePlacementCommand;
use Maggie\Finance\Message\UpdatePlacementCommand;
use Maggie\Finance\Repository\PlacementRepository;
use Maggie\Finance\Service\PlacementRules;
use Maggie\Finance\Service\PlacementSimulator;
use Maggie\Finance\Service\PlacementView;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Never completes a request by deduction: a partial one is refused with
 * `current`, what was given or what the placement is, so the next call can be
 * whole.
 */
#[McpTool(name: 'manage_placements', description: 'List, create, update, delete or simulate the user\'s simulated placements (a PEA, a life insurance, a savings book) — nothing is booked, it is a projection. A placement has a name, initial (paid on day one, integer cents, >= 0), deposit (each deposit, integer cents, >= 0), frequency (monthly, quarterly, yearly: the deposit at the end of each period; once: a single deposit on day one), annualRate (annual return in basis points: 600 = 6 %, 350 = 3.5 %, 0 to 10000) and years (1 to 60). Interest is compounded monthly at (1 + r)^(1/12) - 1 and reinvested. Each placement is read back with final (capital at the end), paid (everything paid in), interest (final - paid), rent (a simulation: final x 4 % / 12, what the capital would give a month withdrawn at 4 % a year — always say it is a simulation) and monthlyDeposit, all integer cents. simulate with placementId answers the saved placement year by year; simulate without it answers a what-if from initial, deposit, frequency, annualRate and years, all required, nothing saved. Nothing is ever deduced: create needs name, initial, deposit, frequency, annualRate and years (initial and deposit cannot both be 0; give 0 explicitly when the user says so); on update only provided fields change. A refused request answers error plus current (what was given, or the placement as it is now): ask the user for what is missing.')]
class ManagePlacementsTool
{
    private const REQUIRED = ['name', 'initial', 'deposit', 'frequency', 'annualRate', 'years'];

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly PlacementRepository $placementRepository,
        private readonly PlacementSimulator $simulator,
        private readonly PlacementRules $rules,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $action,
        ?string $placementId = null,
        ?string $name = null,
        ?int $initial = null,
        ?int $deposit = null,
        ?string $frequency = null,
        ?int $annualRate = null,
        ?int $years = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($name, $initial, $deposit, $frequency, $annualRate, $years),
                'update' => $this->update($placementId, $name, $initial, $deposit, $frequency, $annualRate, $years),
                'delete' => $this->delete($placementId),
                'simulate' => $this->simulate($placementId, $initial, $deposit, $frequency, $annualRate, $years),
                default => self::json(['error' => "Unknown action: {$action}. Use list, create, update, delete, or simulate."]),
            };
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        } catch (HandlerFailedException $e) {
            return self::json(['error' => ($e->getPrevious() ?? $e)->getMessage()]);
        }
    }

    private function list(): string
    {
        $user = $this->userContext->requireUser();

        return self::json(['placements' => PlacementView::list($this->placementRepository->findByUser($user))]);
    }

    private function create(?string $name, ?int $initial, ?int $deposit, ?string $frequency, ?int $annualRate, ?int $years): string
    {
        $user = $this->userContext->requireUser();

        $given = self::given(compact('name', 'initial', 'deposit', 'frequency', 'annualRate', 'years'));
        if (null === $name || null === $initial || null === $deposit || null === $frequency || null === $annualRate || null === $years) {
            $missing = array_values(array_diff(self::REQUIRED, array_keys($given)));

            return self::json(['error' => 'Missing for create: '.implode(', ', $missing).'. Ask the user, never assume.', 'current' => $given]);
        }

        try {
            $placement = $this->bus->dispatch(new CreatePlacementCommand(
                userId: (string) $user->getId(),
                name: $name,
                initial: $initial,
                deposit: $deposit,
                frequency: $frequency,
                annualRate: $annualRate,
                years: $years,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            return self::json(['error' => ($e->getPrevious() ?? $e)->getMessage(), 'current' => $given]);
        }

        return self::json(['success' => true, 'placement' => PlacementView::of($placement)]);
    }

    private function update(?string $placementId, ?string $name, ?int $initial, ?int $deposit, ?string $frequency, ?int $annualRate, ?int $years): string
    {
        if (null === $placementId) {
            return self::json(['error' => 'placementId is required for update.']);
        }

        $user = $this->userContext->requireUser();
        $before = $this->placementRepository->findOneOfUser($user, $placementId);
        if (null === $before) {
            return self::json(['error' => "Placement not found: {$placementId}"]);
        }
        // Read before the handler touches the entity: the state it was refused against.
        $current = PlacementView::of($before);

        try {
            $placement = $this->bus->dispatch(new UpdatePlacementCommand(
                userId: (string) $user->getId(),
                placementId: $placementId,
                name: $name,
                initial: $initial,
                deposit: $deposit,
                frequency: $frequency,
                annualRate: $annualRate,
                years: $years,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            return self::json(['error' => ($e->getPrevious() ?? $e)->getMessage(), 'current' => $current]);
        }

        return self::json(['success' => true, 'placement' => PlacementView::of($placement)]);
    }

    private function delete(?string $placementId): string
    {
        if (null === $placementId) {
            return self::json(['error' => 'placementId is required for delete.']);
        }

        $this->bus->dispatch(new DeletePlacementCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            placementId: $placementId,
        ));

        return self::json(['success' => true]);
    }

    private function simulate(?string $placementId, ?int $initial, ?int $deposit, ?string $frequency, ?int $annualRate, ?int $years): string
    {
        $user = $this->userContext->requireUser();

        if (null !== $placementId) {
            $placement = $this->placementRepository->findOneOfUser($user, $placementId);
            if (null === $placement) {
                return self::json(['error' => "Placement not found: {$placementId}"]);
            }

            return self::json(['placement' => PlacementView::of($placement), 'simulation' => self::yearly($this->simulator->of($placement))]);
        }

        $given = self::given(compact('initial', 'deposit', 'frequency', 'annualRate', 'years'));
        if (null === $initial || null === $deposit || null === $frequency || null === $annualRate || null === $years) {
            $missing = array_values(array_diff(['initial', 'deposit', 'frequency', 'annualRate', 'years'], array_keys($given)));

            return self::json(['error' => 'Give placementId, or every one of: '.implode(', ', $missing).' for a what-if. Ask the user, never assume.', 'current' => $given]);
        }

        try {
            $parsed = $this->rules->frequency($frequency);
            $this->rules->assert($initial, $deposit, $annualRate, $years);
        } catch (\DomainException $e) {
            return self::json(['error' => $e->getMessage(), 'current' => $given]);
        }

        return self::json(['simulation' => self::yearly($this->simulator->simulate($initial, $deposit, $parsed, $annualRate, $years))]);
    }

    /**
     * The curve one point a year: enough for Maggie to tell, a twelfth of the
     * tokens.
     *
     * @param array{final: int, paid: int, interest: int, rent: int, curve: list<array{month: int, capital: int, paid: int}>} $simulation
     *
     * @return array<string, mixed>
     */
    private static function yearly(array $simulation): array
    {
        $simulation['curve'] = array_values(array_map(
            static fn (array $point): array => ['year' => intdiv($point['month'], 12), 'capital' => $point['capital'], 'paid' => $point['paid']],
            array_filter($simulation['curve'], static fn (array $point): bool => 0 === $point['month'] % 12),
        ));

        return $simulation;
    }

    /**
     * @param array<string, mixed> $values
     *
     * @return array<string, mixed>
     */
    private static function given(array $values): array
    {
        return array_filter($values, static fn (mixed $value): bool => null !== $value);
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, \JSON_THROW_ON_ERROR);
    }
}
