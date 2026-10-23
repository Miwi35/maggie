<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Placement;
use Maggie\Finance\Enum\PlacementFrequency;

/**
 * A placement month by month (MAG-555): interest compounded and reinvested
 * every month at the monthly rate equivalent to the annual one,
 * (1 + r)^(1/12) − 1; a periodic deposit lands at the end of its period, after
 * that month's interest; a single one on day one, with the initial payment.
 *
 * Answers `{final, paid, interest, rent, curve: [{month, capital, paid}]}` in
 * integer cents, month 0 to years × 12. `rent` is the 4 % withdrawal
 * convention: final × 4 % / 12 a month — a simulation, never a promise.
 */
final class PlacementSimulator
{
    /** The yearly withdrawal rate of the rent convention, in basis points. */
    public const WITHDRAWAL_RATE_BASIS_POINTS = 400;

    /**
     * @return array{final: int, paid: int, interest: int, rent: int, curve: list<array{month: int, capital: int, paid: int}>}
     */
    public function simulate(int $initial, int $deposit, PlacementFrequency $frequency, int $annualRateBasisPoints, int $years): array
    {
        $monthlyRate = (1 + $annualRateBasisPoints / 10000) ** (1 / 12) - 1;
        $period = $frequency->periodMonths();

        $capital = (float) $initial;
        $paid = $initial;
        if (PlacementFrequency::Once === $frequency) {
            $capital += $deposit;
            $paid += $deposit;
        }

        $curve = [['month' => 0, 'capital' => self::cents($capital), 'paid' => $paid]];
        for ($month = 1; $month <= $years * 12; ++$month) {
            $capital *= 1 + $monthlyRate;
            if (0 !== $period && 0 === $month % $period) {
                $capital += $deposit;
                $paid += $deposit;
            }
            $curve[] = ['month' => $month, 'capital' => self::cents($capital), 'paid' => $paid];
        }

        $final = self::cents($capital);

        return [
            'final' => $final,
            'paid' => $paid,
            'interest' => $final - $paid,
            'rent' => self::rent($final),
            'curve' => $curve,
        ];
    }

    /** @return array{final: int, paid: int, interest: int, rent: int, curve: list<array{month: int, capital: int, paid: int}>} */
    public function of(Placement $placement): array
    {
        return $this->simulate(
            $placement->getInitial(),
            $placement->getDeposit(),
            $placement->getFrequency(),
            $placement->getAnnualRate(),
            $placement->getYears(),
        );
    }

    /**
     * A float capital as integer cents, held at PHP_INT_MAX: 100 % over 60
     * years outgrows an int, and a cast beyond it is garbage.
     */
    private static function cents(float $capital): int
    {
        return $capital >= (float) \PHP_INT_MAX ? \PHP_INT_MAX : (int) round($capital);
    }

    /** What a capital would give a month, withdrawn at 4 % a year. */
    public static function rent(int $capital): int
    {
        return (int) round($capital * self::WITHDRAWAL_RATE_BASIS_POINTS / 10000 / 12);
    }
}
