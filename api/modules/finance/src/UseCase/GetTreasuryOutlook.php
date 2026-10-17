<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Enum\AccountType;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Repository\TreasurySettingsRepository;

/**
 * The treasury until the salary comes: the delay, the projected balance day
 * by day, the overdraft ahead and how much to advance from which savings.
 *
 * It only reads what is entered (shape decision 17): the balances of the open
 * checking accounts, their planned and committed movements from today on,
 * the occurrences the owner's confirmed recurring series still expect
 * (MAG-307, `source` = `recurring`; one with a line attached is that line's,
 * never counted twice) and the expected salary. No average, no extrapolation
 * — every balance of the projection is yesterday's plus the day's movements,
 * and an overdraft one cannot recompute by hand is one nobody believes.
 *
 * The projection runs from today to the end of the next month, or to the day
 * the salary is expected if that is later — or further, to the horizon asked
 * for in months ({@see MAX_MONTHS}, a year at most): it is computed on demand,
 * never stored. A late salary has no date left: it is awaited on the next
 * expected day after today, the latest the schedule says it can come. The
 * salary itself is in the rows through the series that bring it in.
 *
 * The overdraft and the advice look at the whole window: the lowest balance
 * of the rows, whenever it comes. Each row also carries the cumulative
 * inflows and outflows since today, so that the gap between what comes in
 * and what goes out can be drawn; no verdict is drawn from them here.
 *
 * The advice names the savings to take from, the largest first; a cushion
 * account comes last and says so — breaking into the safety net starts a
 * recharge plan (M7).
 *
 * A pure read: the treasury settings are not created here. Its fields are read
 * by the mobile home (`balanceBeforeSalary`) and the treasury screens: keep them.
 */
class GetTreasuryOutlook
{
    public const int MAX_MONTHS = 12;

    public const int MAX_DAYS = 366;

    private const array SOURCE_TYPES = [AccountType::Savings, AccountType::Investment];

    public function __construct(
        private readonly GetSalaryDelay $getSalaryDelay,
        private readonly GetSavingsAdvances $getSavingsAdvances,
        private readonly TreasurySettingsRepository $settingsRepository,
        private readonly AccountRepository $accountRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly GetRecurringOccurrences $getRecurringOccurrences,
    ) {
    }

    /**
     * @param int|null $months how far to project, 1 to {@see MAX_MONTHS}; null for the end of the next month
     *
     * @return array{
     *     today: string,
     *     salary: array{isConfigured: bool, expectedOn: string|null, referenceCents: int|null, isReceived: bool, isLate: bool, delayDays: int, shortfallCents: int},
     *     balanceCents: int,
     *     projection: list<array{date: string, balanceCents: int, cumulativeInflowCents: int, cumulativeOutflowCents: int, movements: list<array{id: string, label: string, amountCents: int, status: string, accountId: string, accountName: string, source: string}>}>,
     *     balanceBeforeSalary: array{onDate: string, balanceCents: int}|null,
     *     overdraft: array{isForecast: bool, onDate: string|null, worstBalanceCents: int, limitCents: int},
     *     advice: array{amountCents: int, sourceAccountId: string|null, sourceAccountName: string|null, sourceBalanceCents: int, sourceAmountCents: int, isCushion: bool, alternatives: list<array{accountId: string, accountName: string, balanceCents: int, amountCents: int, isCushion: bool}>, uncoveredCents: int, touchesCushion: bool}|null,
     *     outstandingAdvancesCents: int
     * }
     */
    public function execute(User $user, ?\DateTimeImmutable $today = null, ?int $months = null): array
    {
        $today = ($today ?? new \DateTimeImmutable('today'))->setTime(0, 0);
        if (null !== $months && ($months < 1 || $months > self::MAX_MONTHS)) {
            throw new \InvalidArgumentException(sprintf('months must be between 1 and %d.', self::MAX_MONTHS));
        }

        $delay = $this->getSalaryDelay->execute($user, $today);
        $limit = $this->settingsRepository->findOneByUser($user)?->getOverdraftLimitCents() ?? 0;
        $accounts = array_values(array_filter(
            $this->accountRepository->findByUser($user),
            static fn (Account $account): bool => !$account->isClosed(),
        ));
        $balance = array_sum(array_map(
            static fn (Account $account): int => $account->getBalanceCents(),
            array_filter($accounts, static fn (Account $account): bool => AccountType::Checking === $account->getType()),
        ));
        $outstanding = $this->getSavingsAdvances->execute($user)['totalOutstandingCents'];

        if (!$delay['isConfigured'] || null === $delay['salaryExpectedDay']) {
            return [
                'today' => $today->format('Y-m-d'),
                'salary' => [
                    'isConfigured' => false,
                    'expectedOn' => null,
                    'referenceCents' => $delay['referenceCents'],
                    'isReceived' => false,
                    'isLate' => false,
                    'delayDays' => 0,
                    'shortfallCents' => 0,
                ],
                'balanceCents' => $balance,
                'projection' => [],
                'balanceBeforeSalary' => null,
                'overdraft' => ['isForecast' => false, 'onDate' => null, 'worstBalanceCents' => $balance, 'limitCents' => $limit],
                'advice' => null,
                'outstandingAdvancesCents' => $outstanding,
            ];
        }

        $day = $delay['salaryExpectedDay'];
        $current = $delay['months'][1];
        $thisMonth = $today->modify('first day of this month');
        $nextExpected = GetSalaryDelay::expectedOn($thisMonth->modify('+1 month'), $day);
        $currentExpected = new \DateTimeImmutable($current['expectedOn']);
        $isReceived = $current['isReceived'];

        if ($delay['isLate']) {
            $open = array_values(array_filter($delay['months'], static fn (array $month): bool => $month['delayDays'] > 0));
            $expectedOn = new \DateTimeImmutable($open[0]['expectedOn']);
            $arrival = $currentExpected > $today ? $currentExpected : $nextExpected;
        } else {
            $expectedOn = $isReceived ? $nextExpected : $currentExpected;
            $arrival = $expectedOn;
        }

        $projection = $this->project($user, $today, $arrival, $balance, $accounts, $months);
        $beforeArrival = self::beforeArrival($balance, $projection, $arrival);
        $overdraft = self::overdraft($today, $balance, $projection, $limit);

        return [
            'today' => $today->format('Y-m-d'),
            'salary' => [
                'isConfigured' => true,
                'expectedOn' => $expectedOn->format('Y-m-d'),
                'referenceCents' => $delay['referenceCents'],
                'isReceived' => $isReceived,
                'isLate' => $delay['isLate'],
                'delayDays' => $delay['delayDays'],
                'shortfallCents' => $delay['shortfallCents'],
            ],
            'balanceCents' => $balance,
            'projection' => $projection,
            'balanceBeforeSalary' => self::balanceBeforeSalary($today, $balance, $projection, $beforeArrival, $delay['isLate'] ? null : $expectedOn),
            'overdraft' => $overdraft,
            'advice' => $overdraft['isForecast']
                ? self::advise(-$limit - $overdraft['worstBalanceCents'], $accounts)
                : null,
            'outstandingAdvancesCents' => $outstanding,
        ];
    }

    /**
     * One row per day from today to the horizon — the end of the next month, or
     * $arrival if later, or $months months on — at most {@see MAX_DAYS}: the
     * day's balance is the previous one plus the day's movements — the entered
     * lines first (`source` = `transaction`), then the occurrences still
     * expected (`source` = `recurring`, `status` = `expected`, `id` = the
     * series') on the open checking accounts, like the lines.
     *
     * @param list<Account> $accounts
     *
     * @return list<array{date: string, balanceCents: int, cumulativeInflowCents: int, cumulativeOutflowCents: int, movements: list<array{id: string, label: string, amountCents: int, status: string, accountId: string, accountName: string, source: string}>}>
     */
    private function project(User $user, \DateTimeImmutable $today, \DateTimeImmutable $arrival, int $balance, array $accounts, ?int $months): array
    {
        $horizon = null === $months
            ? $today->modify('first day of this month')->modify('+2 months')
            : $today->modify(sprintf('+%d months', $months));
        $end = min(max($arrival->modify('+1 day'), $horizon), $today->modify(sprintf('+%d days', self::MAX_DAYS)));

        $checking = [];
        foreach ($accounts as $account) {
            if (AccountType::Checking === $account->getType()) {
                $checking[(string) $account->getId()] = $account;
            }
        }

        /** @var array<string, list<array{id: string, label: string, amountCents: int, status: string, accountId: string, accountName: string, source: string}>> $byDay */
        $byDay = [];
        foreach ($this->transactionRepository->findForecastMovementsBetween($user, $today, $end) as $movement) {
            $byDay[$movement->getBookedAt()->format('Y-m-d')][] = [
                'id' => (string) $movement->getId(),
                'label' => $movement->getLabel(),
                'amountCents' => $movement->getAmountCents(),
                'status' => $movement->getStatus()->value,
                'accountId' => (string) $movement->getAccount()->getId(),
                'accountName' => $movement->getAccount()->getName(),
                'source' => 'transaction',
            ];
        }

        foreach ($this->getRecurringOccurrences->execute($user, $today, $end) as $occurrence) {
            $account = $checking[$occurrence['accountId']] ?? null;
            if (null === $account) {
                continue;
            }

            $byDay[$occurrence['date']][] = [
                'id' => $occurrence['recurringOperationId'],
                'label' => $occurrence['label'],
                'amountCents' => $occurrence['amountCents'],
                'status' => 'expected',
                'accountId' => $occurrence['accountId'],
                'accountName' => $account->getName(),
                'source' => 'recurring',
            ];
        }

        $rows = [];
        $inflow = 0;
        $outflow = 0;
        for ($date = $today; $date < $end; $date = $date->modify('+1 day')) {
            $movements = $byDay[$date->format('Y-m-d')] ?? [];
            $cents = array_column($movements, 'amountCents');
            $balance += array_sum($cents);
            $inflow += array_sum(array_filter($cents, static fn (int $c): bool => $c > 0));
            $outflow -= array_sum(array_filter($cents, static fn (int $c): bool => $c < 0));
            $rows[] = [
                'date' => $date->format('Y-m-d'),
                'balanceCents' => $balance,
                'cumulativeInflowCents' => $inflow,
                'cumulativeOutflowCents' => $outflow,
                'movements' => $movements,
            ];
        }

        return $rows;
    }

    /**
     * The first day the balance falls below minus the allowed overdraft —
     * today when it already is — and the lowest balance on the way.
     *
     * @param list<array{date: string, balanceCents: int, movements: list<mixed>}> $projection
     *
     * @return array{isForecast: bool, onDate: string|null, worstBalanceCents: int, limitCents: int}
     */
    private static function overdraft(\DateTimeImmutable $today, int $balance, array $projection, int $limit): array
    {
        $onDate = $balance < -$limit ? $today->format('Y-m-d') : null;
        $worst = $balance;

        foreach ($projection as $row) {
            $worst = min($worst, $row['balanceCents']);
            if (null === $onDate && $row['balanceCents'] < -$limit) {
                $onDate = $row['date'];
            }
        }

        return ['isForecast' => null !== $onDate, 'onDate' => $onDate, 'worstBalanceCents' => $worst, 'limitCents' => $limit];
    }

    /**
     * The balance the salary's day opens on, less the debits that very day —
     * what is left before the credit is counted on — or null when the day is
     * past the projection.
     *
     * @param list<array{date: string, balanceCents: int, movements: list<array{amountCents: int}>}> $projection
     *
     * @return array{date: string, balanceCents: int}|null
     */
    private static function beforeArrival(int $balance, array $projection, \DateTimeImmutable $arrival): ?array
    {
        $day = $arrival->format('Y-m-d');
        $opening = $balance;

        foreach ($projection as $row) {
            if ($row['date'] === $day) {
                $debits = array_sum(array_filter(array_column($row['movements'], 'amountCents'), static fn (int $cents): bool => $cents < 0));

                return ['date' => $day, 'balanceCents' => $opening + $debits];
            }
            $opening = $row['balanceCents'];
        }

        return null;
    }

    /**
     * The balance the eve of the expected salary, as the projection gives it,
     * less the debits of the salary's own day ($beforeArrival); today's when
     * the salary is late (null $expectedOn), or when it is expected today.
     *
     * @param list<array{date: string, balanceCents: int, movements: list<mixed>}> $projection
     * @param array{date: string, balanceCents: int}|null                          $beforeArrival
     *
     * @return array{onDate: string, balanceCents: int}
     */
    private static function balanceBeforeSalary(\DateTimeImmutable $today, int $balance, array $projection, ?array $beforeArrival, ?\DateTimeImmutable $expectedOn): array
    {
        $todayKey = $today->format('Y-m-d');

        if (null === $expectedOn) {
            return ['onDate' => $todayKey, 'balanceCents' => $projection[0]['balanceCents'] ?? $balance];
        }

        $eve = max($today, $expectedOn->modify('-1 day'))->format('Y-m-d');
        if (null !== $beforeArrival) {
            return ['onDate' => $eve, 'balanceCents' => $beforeArrival['balanceCents']];
        }

        $last = [] === $projection ? null : $projection[array_key_last($projection)];

        return null === $last
            ? ['onDate' => $todayKey, 'balanceCents' => $balance]
            : ['onDate' => $last['date'], 'balanceCents' => $last['balanceCents']];
    }

    /**
     * What to advance to stay above the limit, from the savings with the
     * largest balance; the next ones, largest first and cushions last, make up
     * what it cannot cover.
     *
     * @param list<Account> $accounts
     *
     * @return array{amountCents: int, sourceAccountId: string|null, sourceAccountName: string|null, sourceBalanceCents: int, sourceAmountCents: int, isCushion: bool, alternatives: list<array{accountId: string, accountName: string, balanceCents: int, amountCents: int, isCushion: bool}>, uncoveredCents: int, touchesCushion: bool}
     */
    private static function advise(int $needed, array $accounts): array
    {
        $sources = array_values(array_filter(
            $accounts,
            static fn (Account $account): bool => \in_array($account->getType(), self::SOURCE_TYPES, true) && $account->getBalanceCents() > 0,
        ));
        usort($sources, static fn (Account $a, Account $b): int => [$a->isCushion(), $b->getBalanceCents(), $a->getName()]
            <=> [$b->isCushion(), $a->getBalanceCents(), $b->getName()]);

        $left = $needed;
        $takes = [];
        foreach ($sources as $source) {
            if ($left <= 0) {
                break;
            }
            $amount = min($left, $source->getBalanceCents());
            $left -= $amount;
            $takes[] = [
                'accountId' => (string) $source->getId(),
                'accountName' => $source->getName(),
                'balanceCents' => $source->getBalanceCents(),
                'amountCents' => $amount,
                'isCushion' => $source->isCushion(),
            ];
        }

        $first = array_shift($takes);

        return [
            'amountCents' => $needed,
            'sourceAccountId' => $first['accountId'] ?? null,
            'sourceAccountName' => $first['accountName'] ?? null,
            'sourceBalanceCents' => $first['balanceCents'] ?? 0,
            'sourceAmountCents' => $first['amountCents'] ?? 0,
            'isCushion' => $first['isCushion'] ?? false,
            'alternatives' => $takes,
            'uncoveredCents' => $left,
            'touchesCushion' => ($first['isCushion'] ?? false) || \in_array(true, array_column($takes, 'isCushion'), true),
        ];
    }
}
