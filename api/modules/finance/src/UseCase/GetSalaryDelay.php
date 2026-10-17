<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Category\StandardCategories;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Repository\TreasurySettingsRepository;
use Symfony\Component\String\UnicodeString;

/**
 * Whether the salary is late, for the previous month and the current one.
 *
 * A delay is a period, not a flag (shape decision 13): it opens the day after
 * the expected day and closes when the credits filed under « Salaire » reach
 * 90 % of the reference salary — 90 and not 100, because a transfer is
 * rounded or docked a fee, and a delay that never closes is worse than one
 * closed a day early. A bonus is in « Primes » and closes nothing.
 *
 * A credit fills the open months oldest first: one transfer at ~200 % on the
 * 26th closes the previous month, then the current one. A month only takes
 * the credits booked from a few days before its expected day, so a salary
 * paid early because the 25th is a Sunday still counts.
 *
 * What counts as the salary: a credit filed under « Salaire » — which one
 * attached to the salary series is, it takes the series' category — or, a
 * payer's transfer being rarely filed, any other credit within 10 % of the
 * reference booked from {@see NEAR_DAYS} days before the expected day on, so
 * a salary that comes late closes its delay the day it lands, whatever its
 * label. A bonus, a rente, a transfer between accounts, or a credit whose
 * label carries the owner's own name (a transfer from oneself) are not
 * recognised by their amount.
 *
 * Without an expected day, or without a reference salary, nothing is late:
 * `isConfigured` is false. A pure read — the settings are not created here.
 * Its fields are read by the monthly review and the mobile home: keep them.
 */
class GetSalaryDelay
{
    /** Share of the reference that closes a month, in percent. */
    public const int CLOSING_PERCENT = 90;

    /** How many days before its expected day a credit may still pay a month. */
    public const int EARLY_DAYS = 3;

    /** How many days before its expected day a credit known by its amount alone may pay a month. */
    public const int NEAR_DAYS = 5;

    public function __construct(
        private readonly TreasurySettingsRepository $settingsRepository,
        private readonly GetReferenceSalary $getReferenceSalary,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    /**
     * @return array{
     *     isConfigured: bool,
     *     salaryExpectedDay: int|null,
     *     referenceCents: int|null,
     *     isLate: bool,
     *     delayDays: int,
     *     shortfallCents: int,
     *     months: list<array{month: string, expectedOn: string, receivedCents: int, referenceCents: int, isReceived: bool, receivedOn: string|null, isDue: bool, delayDays: int, shortfallCents: int}>
     * }
     */
    public function execute(User $user, ?\DateTimeImmutable $today = null): array
    {
        $today = ($today ?? new \DateTimeImmutable('today'))->setTime(0, 0);

        $day = $this->settingsRepository->findOneByUser($user)?->getSalaryExpectedDay();
        $reference = null === $day ? null : $this->getReferenceSalary->execute($user, $today)['monthlyCents'];

        if (null === $day || null === $reference || $reference <= 0) {
            return [
                'isConfigured' => false,
                'salaryExpectedDay' => $day,
                'referenceCents' => $reference,
                'isLate' => false,
                'delayDays' => 0,
                'shortfallCents' => 0,
                'months' => [],
            ];
        }

        $ownName = self::fold($user->getName());
        $currentMonth = $today->modify('first day of this month');
        $expected = [
            self::expectedOn($currentMonth->modify('-1 month'), $day),
            self::expectedOn($currentMonth, $day),
        ];

        $from = $expected[0]->modify(sprintf('-%d days', max(self::EARLY_DAYS, self::NEAR_DAYS)));
        $credits = [];
        foreach ($this->transactionRepository->findCreditsBetween($user, $from, $today) as $credit) {
            $kind = self::kindOf($credit, $reference, $ownName);
            if (null !== $kind) {
                $credits[] = ['transaction' => $credit, 'byAmount' => 'amount' === $kind];
            }
        }

        $months = $this->allocate($expected, $credits, $reference, $today);
        $open = array_filter($months, static fn (array $month) => $month['delayDays'] > 0);

        return [
            'isConfigured' => true,
            'salaryExpectedDay' => $day,
            'referenceCents' => $reference,
            'isLate' => [] !== $open,
            'delayDays' => [] === $open ? 0 : max(array_column($open, 'delayDays')),
            'shortfallCents' => array_sum(array_column($open, 'shortfallCents')),
            'months' => $months,
        ];
    }

    /**
     * Pours the credits into the months, oldest month first, each one until
     * it closes and at most up to the reference; what a credit has left goes
     * to the next month, if that month may take it.
     *
     * @param list<\DateTimeImmutable>                              $expected
     * @param list<array{transaction: Transaction, byAmount: bool}> $credits
     *
     * @return list<array{month: string, expectedOn: string, receivedCents: int, referenceCents: int, isReceived: bool, receivedOn: string|null, isDue: bool, delayDays: int, shortfallCents: int}>
     */
    private function allocate(array $expected, array $credits, int $reference, \DateTimeImmutable $today): array
    {
        $left = array_map(static fn (array $credit) => $credit['transaction']->getAmountCents(), $credits);
        $months = [];

        foreach ($expected as $expectedOn) {
            $received = 0;
            $receivedOn = null;

            foreach ($credits as $i => $credit) {
                // A closed month takes no more: what it lacks to 100 % is
                // not owed, or the next salary would come up short.
                if (self::closes($received, $reference)) {
                    break;
                }
                if (0 === $left[$i] || !self::mayPay($credit['transaction'], $credit['byAmount'], $expectedOn)) {
                    continue;
                }

                $taken = min($left[$i], $reference - $received);
                $left[$i] -= $taken;
                $received += $taken;

                if (null === $receivedOn && self::closes($received, $reference)) {
                    $receivedOn = $credit['transaction']->getBookedAt()->format('Y-m-d');
                }
            }

            $isReceived = self::closes($received, $reference);
            $isDue = $today > $expectedOn;
            $isOpen = $isDue && !$isReceived;

            $months[] = [
                'month' => $expectedOn->format('Y-m'),
                'expectedOn' => $expectedOn->format('Y-m-d'),
                'receivedCents' => $received,
                'referenceCents' => $reference,
                'isReceived' => $isReceived,
                'receivedOn' => $receivedOn,
                'isDue' => $isDue,
                'delayDays' => $isOpen ? (int) $expectedOn->diff($today)->days : 0,
                'shortfallCents' => $isOpen ? $reference - $received : 0,
            ];
        }

        return $months;
    }

    /**
     * `category` for a credit filed as the salary, `amount` for
     * one only its size recognises, null for what is not a salary.
     */
    private static function kindOf(Transaction $credit, int $reference, string $ownName): ?string
    {
        $category = $credit->getCategory();
        if ($category?->isPassiveIncome() ?? false) {
            return null;
        }

        if ($category?->isStandard(StandardCategories::SALARY) ?? false) {
            return 'category';
        }

        $isBonus = $category?->isStandard(StandardCategories::BONUS) ?? false;
        $withinTenPercent = abs($credit->getAmountCents() - $reference) * 100 <= $reference * (100 - self::CLOSING_PERCENT);

        return !$isBonus && $withinTenPercent && !self::isFromOneself($credit, $ownName) ? 'amount' : null;
    }

    /** Every word of the owner's name (two at least) is in the label: a transfer from oneself, not a payer's. */
    private static function isFromOneself(Transaction $credit, string $ownName): bool
    {
        $words = array_values(array_filter(explode(' ', $ownName), static fn (string $word): bool => mb_strlen($word) > 1));
        if (\count($words) < 2) {
            return false;
        }

        $label = self::fold($credit->getLabel());
        foreach ($words as $word) {
            if (!str_contains($label, $word)) {
                return false;
            }
        }

        return true;
    }

    private static function fold(string $text): string
    {
        return (new UnicodeString(trim($text)))->ascii()->lower()->collapseWhitespace()->toString();
    }

    /** A credit pays a month from a few days before its expected day on — a few more for one known by its amount only. */
    private static function mayPay(Transaction $credit, bool $byAmount, \DateTimeImmutable $expectedOn): bool
    {
        return $credit->getBookedAt() >= self::firstPayableDay($expectedOn, $byAmount ? self::NEAR_DAYS : self::EARLY_DAYS);
    }

    private static function closes(int $received, int $reference): bool
    {
        return $received * 100 >= $reference * self::CLOSING_PERCENT;
    }

    /** The expected day of a month, brought back to its last day when the month is shorter. */
    public static function expectedOn(\DateTimeImmutable $month, int $day): \DateTimeImmutable
    {
        $first = $month->modify('first day of this month');

        return $first->modify(sprintf('+%d days', min($day, (int) $first->format('t')) - 1));
    }

    private static function firstPayableDay(\DateTimeImmutable $expectedOn, int $days): \DateTimeImmutable
    {
        return $expectedOn->modify(sprintf('-%d days', $days));
    }
}
