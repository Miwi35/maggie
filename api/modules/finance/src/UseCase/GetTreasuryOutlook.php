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
 * The projection runs from today to the day before the salary is expected,
 * {@see MAX_DAYS} days at most. A late salary has no date left: it runs to the
 * next expected day after today, the latest the schedule says it can come.
 *
 * The advice is given only while a salary is awaited: once it is in, a dip
 * is a budget matter, and a transfer from savings would not be an advance.
 * The largest savings source comes first; a cushion account comes last and
 * says so — breaking into the safety net starts a recharge plan (M7).
 *
 * A pure read: the treasury settings are not created here. Its fields are read
 * by the mobile home (`balanceBeforeSalary`) and the treasury screens: keep them.
 */
class GetTreasuryOutlook
{
    public const int MAX_DAYS = 60;

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
     * @return array{
     *     today: string,
     *     salary: array{isConfigured: bool, expectedOn: string|null, referenceCents: int|null, isReceived: bool, isLate: bool, delayDays: int, shortfallCents: int},
     *     balanceCents: int,
     *     projection: list<array{date: string, balanceCents: int, movements: list<array{id: string, label: string, amountCents: int, status: string, accountId: string, accountName: string, source: string}>}>,
     *     balanceBeforeSalary: array{onDate: string, balanceCents: int}|null,
     *     overdraft: array{isForecast: bool, onDate: string|null, worstBalanceCents: int, limitCents: int},
     *     advice: array{amountCents: int, sourceAccountId: string|null, sourceAccountName: string|null, sourceBalanceCents: int, sourceAmountCents: int, isCushion: bool, alternatives: list<array{accountId: string, accountName: string, balanceCents: int, amountCents: int, isCushion: bool}>, uncoveredCents: int, touchesCushion: bool}|null,
     *     outstandingAdvancesCents: int
     * }
     */
    public function execute(User $user, ?\DateTimeImmutable $today = null): array
    {
        $today = ($today ?? new \DateTimeImmutable('today'))->setTime(0, 0);

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

        $projection = $this->project($user, $today, $arrival, $balance, $accounts);
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
            'balanceBeforeSalary' => self::balanceBeforeSalary($today, $balance, $projection, $delay['isLate'] ? null : $expectedOn),
            'overdraft' => $overdraft,
            'advice' => $overdraft['isForecast'] && !$isReceived
                ? self::advise(-$limit - $overdraft['worstBalanceCents'], $accounts)
                : null,
            'outstandingAdvancesCents' => $outstanding,
        ];
    }

    /**
     * One row per day from today to the day before $arrival, at most
     * {@see MAX_DAYS}: the day's balance is the previous one plus the day's
     * movements — the entered lines first (`source` = `transaction`), then the
     * occurrences still expected (`source` = `recurring`, `status` = `expected`,
     * `id` = the series') on the open checking accounts, like the lines.
     *
     * @param list<Account> $accounts
     *
     * @return list<array{date: string, balanceCents: int, movements: list<array{id: string, label: string, amountCents: int, status: string, accountId: string, accountName: string, source: string}>}>
     */
    private function project(User $user, \DateTimeImmutable $today, \DateTimeImmutable $arrival, int $balance, array $accounts): array
    {
        $end = min($arrival, $today->modify(sprintf('+%d days', self::MAX_DAYS)));
        if ($end <= $today) {
            return [];
        }

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
        for ($date = $today; $date < $end; $date = $date->modify('+1 day')) {
            $movements = $byDay[$date->format('Y-m-d')] ?? [];
            $balance += array_sum(array_column($movements, 'amountCents'));
            $rows[] = [
                'date' => $date->format('Y-m-d'),
                'balanceCents' => $balance,
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
     * The balance the eve of the expected salary, as the projection gives it;
     * today's when the salary is late (null $expectedOn) or expected today.
     *
     * @param list<array{date: string, balanceCents: int, movements: list<mixed>}> $projection
     *
     * @return array{onDate: string, balanceCents: int}
     */
    private static function balanceBeforeSalary(\DateTimeImmutable $today, int $balance, array $projection, ?\DateTimeImmutable $expectedOn): array
    {
        $eve = null === $expectedOn ? $today->format('Y-m-d') : $expectedOn->modify('-1 day')->format('Y-m-d');

        foreach ($projection as $row) {
            if ($row['date'] === $eve) {
                return ['onDate' => $row['date'], 'balanceCents' => $row['balanceCents']];
            }
        }

        $last = [] === $projection ? null : $projection[array_key_last($projection)];

        return null === $last || $eve < $today->format('Y-m-d')
            ? ['onDate' => $today->format('Y-m-d'), 'balanceCents' => $projection[0]['balanceCents'] ?? $balance]
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
