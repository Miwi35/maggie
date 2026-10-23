<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

/**
 * The independence projection of a planning (MAG-549): what the planning leaves
 * of the capacity each month is invested; beyond its horizon the last month's
 * rhythm goes on, without the projects not yet bought. Shared by the
 * independence counter (the current planning) and the scenarios (each one's).
 */
final class PlanIndependence
{
    public function __construct(private readonly IndependenceProjector $projector)
    {
    }

    /**
     * @param array{startMonth: string, netCapacityCents: int, months: list<array<string, mixed>>} $plan
     *
     * @return array{startMonth: string, nDate: string|null, curve: list<array{month: string, coverage: float}>, milestones: array<int, string|null>}
     */
    public function project(array $plan, int $passiveIncomeCents, int $lifestyleCents): array
    {
        $investable = array_map(
            static fn (array $month): int => max(0, $month['capacityCents'] - $month['salaryCents']),
            $plan['months'],
        );
        $last = $plan['months'][array_key_last($plan['months'])];
        $tail = max(0, $plan['netCapacityCents'] - $last['loanPaymentsCents']);

        return ['startMonth' => $plan['startMonth']] + $this->projector->project(
            new \DateTimeImmutable($plan['startMonth'].'-01'),
            $passiveIncomeCents,
            $lifestyleCents,
            $investable,
            $tail,
        );
    }

    /**
     * How many months from the start to N, the horizon when it is not reached:
     * a number the scenarios can sort and subtract.
     *
     * @param array{startMonth: string, nDate: string|null} $independence
     */
    public static function monthsToN(array $independence): int
    {
        if (null === $independence['nDate']) {
            return IndependenceProjector::HORIZON_MONTHS;
        }

        return self::monthNumber($independence['nDate']) - self::monthNumber($independence['startMonth']);
    }

    private static function monthNumber(string $month): int
    {
        [$year, $number] = array_map('intval', explode('-', $month));

        return $year * 12 + $number;
    }
}
