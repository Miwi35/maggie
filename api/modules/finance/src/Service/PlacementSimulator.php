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
        $run = $this->run($initial, $deposit, $frequency, $annualRateBasisPoints, $years, []);
        unset($run['withdrawn'], $run['taken']);

        return $run;
    }

    /**
     * The placement with and without the capital a scenario's projects take out
     * of it (MAG-556): a withdrawal at month index m leaves the capital after
     * that month's interest and deposit (month 0: the initial state), never more
     * than the capital there is, and what is taken stops earning.
     *
     * Answers `{curveWithout, curveWith, gapAtEnd, finalWith, rentWith,
     * withdrawn}` in integer cents; the curves are month 0 to years × 12, a
     * withdrawal beyond the duration is ignored.
     *
     * @param array<int, int> $withdrawals month index → cents asked
     *
     * `taken` is what each month really gave, month index → cents: the withdrawals
     * the placement could cover, none beyond its duration
     *
     * @return array{curveWithout: list<array{month: int, capital: int, paid: int}>, curveWith: list<array{month: int, capital: int, paid: int}>, gapAtEnd: int, finalWithout: int, finalWith: int, rentWithout: int, rentWith: int, withdrawn: int, taken: array<int, int>}
     */
    public function withWithdrawals(Placement $placement, array $withdrawals): array
    {
        $without = $this->of($placement);
        $with = $this->run(
            $placement->getInitial(),
            $placement->getDeposit(),
            $placement->getFrequency(),
            $placement->getAnnualRate(),
            $placement->getYears(),
            $withdrawals,
        );

        return [
            'curveWithout' => $without['curve'],
            'curveWith' => $with['curve'],
            'gapAtEnd' => $without['final'] - $with['final'],
            'finalWithout' => $without['final'],
            'finalWith' => $with['final'],
            'rentWithout' => $without['rent'],
            'rentWith' => $with['rent'],
            'withdrawn' => $with['withdrawn'],
            'taken' => $with['taken'],
        ];
    }

    /**
     * @param array<int, int> $withdrawals month index → cents asked
     *
     * @return array{final: int, paid: int, interest: int, rent: int, withdrawn: int, taken: array<int, int>, curve: list<array{month: int, capital: int, paid: int}>}
     */
    private function run(int $initial, int $deposit, PlacementFrequency $frequency, int $annualRateBasisPoints, int $years, array $withdrawals): array
    {
        $monthlyRate = (1 + $annualRateBasisPoints / 10000) ** (1 / 12) - 1;
        $period = $frequency->periodMonths();

        $capital = (float) $initial;
        $paid = $initial;
        if (PlacementFrequency::Once === $frequency) {
            $capital += $deposit;
            $paid += $deposit;
        }

        $withdrawn = 0;
        $takenByMonth = [];
        $take = static function (int $month) use (&$capital, &$withdrawn, &$takenByMonth, $withdrawals): void {
            $taken = min(max(0, $withdrawals[$month] ?? 0), self::cents(floor(max(0.0, $capital))));
            if ($taken > 0) {
                $takenByMonth[$month] = $taken;
            }
            $capital -= $taken;
            $withdrawn += $taken;
        };

        $take(0);
        $curve = [['month' => 0, 'capital' => self::cents($capital), 'paid' => $paid]];
        for ($month = 1; $month <= $years * 12; ++$month) {
            $capital *= 1 + $monthlyRate;
            if (0 !== $period && 0 === $month % $period) {
                $capital += $deposit;
                $paid += $deposit;
            }
            $take($month);
            $curve[] = ['month' => $month, 'capital' => self::cents($capital), 'paid' => $paid];
        }

        $final = self::cents($capital);

        return [
            'final' => $final,
            'paid' => $paid,
            'interest' => $final - $paid,
            'rent' => self::rent($final),
            'withdrawn' => $withdrawn,
            'taken' => $takenByMonth,
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
