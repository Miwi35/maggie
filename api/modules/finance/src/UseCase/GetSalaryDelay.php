<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Repository\TreasurySettingsRepository;

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

        $currentMonth = $today->modify('first day of this month');
        $expected = [
            self::expectedOn($currentMonth->modify('-1 month'), $day),
            self::expectedOn($currentMonth, $day),
        ];

        $credits = array_values(array_filter(
            $this->transactionRepository->findSalaryCreditsBetween($user, self::firstPayableDay($expected[0]), $today),
            static fn (Transaction $credit) => !($credit->getCategory()?->isPassiveIncome() ?? false),
        ));

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
     * Pours the credits into the months, oldest month first, each one up to
     * the reference; what a credit has left goes to the next month, if that
     * month may take it.
     *
     * @param list<\DateTimeImmutable> $expected
     * @param list<Transaction>        $credits
     *
     * @return list<array{month: string, expectedOn: string, receivedCents: int, referenceCents: int, isReceived: bool, receivedOn: string|null, isDue: bool, delayDays: int, shortfallCents: int}>
     */
    private function allocate(array $expected, array $credits, int $reference, \DateTimeImmutable $today): array
    {
        $left = array_map(static fn (Transaction $credit) => $credit->getAmountCents(), $credits);
        $months = [];

        foreach ($expected as $expectedOn) {
            $received = 0;
            $receivedOn = null;

            foreach ($credits as $i => $credit) {
                if ($received >= $reference) {
                    break;
                }
                if (0 === $left[$i] || $credit->getBookedAt() < self::firstPayableDay($expectedOn)) {
                    continue;
                }

                $taken = min($left[$i], $reference - $received);
                $left[$i] -= $taken;
                $received += $taken;

                if (null === $receivedOn && self::closes($received, $reference)) {
                    $receivedOn = $credit->getBookedAt()->format('Y-m-d');
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

    private static function closes(int $received, int $reference): bool
    {
        return $received * 100 >= $reference * self::CLOSING_PERCENT;
    }

    /** The expected day of a month, brought back to its last day when the month is shorter. */
    private static function expectedOn(\DateTimeImmutable $month, int $day): \DateTimeImmutable
    {
        $first = $month->modify('first day of this month');

        return $first->modify(sprintf('+%d days', min($day, (int) $first->format('t')) - 1));
    }

    private static function firstPayableDay(\DateTimeImmutable $expectedOn): \DateTimeImmutable
    {
        return $expectedOn->modify(sprintf('-%d days', self::EARLY_DAYS));
    }
}
