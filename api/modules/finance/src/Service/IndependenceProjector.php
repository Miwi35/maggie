<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

/**
 * The date N, the coverage curve and the dated milestones (MAG-549, rules 9 and
 * 11). Pure: the same figures always give the same projection.
 *
 * Each month the rentes are the ones measured today plus the yield of what has
 * been invested so far; the train de vie stays what it is measured today. What
 * is invested in a month is what the planning leaves of the capacity once the
 * cushion and the projects have taken their share, and it starts yielding the
 * month after. The yield is paid out as rente, not reinvested, and nothing is
 * invested beyond what the planning hands in.
 */
final class IndependenceProjector
{
    /** 20 years: past it N is "not reached", not a date. */
    public const HORIZON_MONTHS = 240;

    /** 4 % a year, until the placements of the user exist (MAG-549). */
    public const YIELD_BPS = 400;

    /** The steps the user is walking towards 100 %. */
    public const MILESTONES = [25, 50, 75, 100];

    /**
     * @param list<int> $investableCents     what is invested each month from the current one, then…
     * @param int       $tailInvestableCents … each month after the list
     *
     * @return array{
     *     nDate: string|null,
     *     curve: list<array{month: string, coverage: float}>,
     *     milestones: array<int, string|null>
     * }
     */
    public function project(
        \DateTimeImmutable $today,
        int $passiveIncomeCents,
        int $lifestyleCents,
        array $investableCents,
        int $tailInvestableCents,
        int $yieldBps = self::YIELD_BPS,
    ): array {
        $milestones = array_fill_keys(self::MILESTONES, null);
        if ($lifestyleCents <= 0) {
            return ['nDate' => null, 'curve' => [], 'milestones' => $milestones];
        }

        $start = $today->modify('first day of this month')->setTime(0, 0);
        $curve = [];
        $nDate = null;
        $capital = 0;

        for ($m = 0; $m < self::HORIZON_MONTHS; ++$m) {
            $month = $start->modify(\sprintf('+%d months', $m))->format('Y-m');
            $rente = $passiveIncomeCents + intdiv($capital * $yieldBps, 120000);

            // Truncated, so that 1.0 means covered and nothing else.
            $curve[] = ['month' => $month, 'coverage' => min(10000, intdiv($rente * 10000, $lifestyleCents)) / 10000.0];

            foreach (self::MILESTONES as $percent) {
                if (null === $milestones[$percent] && $rente * 100 >= $lifestyleCents * $percent) {
                    $milestones[$percent] = $month;
                }
            }

            if ($rente >= $lifestyleCents) {
                $nDate = $month;
                break;
            }

            $capital += max(0, $investableCents[$m] ?? $tailInvestableCents);
        }

        return ['nDate' => $nDate, 'curve' => $curve, 'milestones' => $milestones];
    }

    /**
     * How many months a variant moves N: positive when it comes later, negative
     * when it comes sooner. Null when either is not reached within the horizon,
     * where a number would be a guess.
     *
     * @param array{nDate: string|null} $baseline
     * @param array{nDate: string|null} $variant
     */
    public function nImpactMonths(array $baseline, array $variant): ?int
    {
        if (null === $baseline['nDate'] || null === $variant['nDate']) {
            return null;
        }

        return self::monthNumber($variant['nDate']) - self::monthNumber($baseline['nDate']);
    }

    private static function monthNumber(string $month): int
    {
        [$year, $number] = array_map('intval', explode('-', $month));

        return $year * 12 + $number;
    }
}
