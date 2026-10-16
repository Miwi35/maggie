<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * Everything the month is made of, in one read: the week and month scores
 * with their rhythm, the three tiles of the home (accounts, budget, planning),
 * and the older blocks the agent still reads in one call — what is in the
 * accounts, twelve months of money in and out, the budget gauges, the biggest
 * spending posts against last month, and what is left to save.
 *
 * It composes the use cases that already exist rather than recomputing
 * anything: one answer, one set of rules. A pure read: it publishes nothing
 * and indexes nothing.
 */
class GetFinanceDashboard
{
    private const TREND_MONTHS = 12;
    private const TOP_POSTS = 5;

    public function __construct(
        private readonly GetDailyScore $getDailyScore,
        private readonly GetFinanceScores $getFinanceScores,
        private readonly GetBudgetStatus $getBudgetStatus,
        private readonly GetCushionStatus $getCushionStatus,
        private readonly GetReferenceSalary $getReferenceSalary,
        private readonly GetTreasuryOutlook $getTreasuryOutlook,
        private readonly GetDebtTimeline $getDebtTimeline,
        private readonly GetIndependenceCounter $getIndependenceCounter,
        private readonly AccountRepository $accountRepository,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(User $user, int $year, int $month): array
    {
        $monthStart = new \DateTimeImmutable(sprintf('%04d-%02d-01', $year, $month));
        $accounts = $this->accountRepository->findByUser($user);
        $balance = $this->balance($accounts);
        $budgets = $this->getBudgetStatus->execute($user, $year, $month)['budgets'];
        $savingCapacity = $this->getDebtTimeline->execute($user)['savingCapacity'];
        // Not a figure of the month shown: the counter is measured over the
        // last complete months, like the saving capacity beside it.
        $independence = $this->getIndependenceCounter->execute($user);

        return [
            'year' => $year,
            'month' => $month,
            'scores' => $this->getFinanceScores->execute($user, $this->scoresDate($user, $year, $month)),
            'tiles' => [
                'accounts' => $this->accountsTile($balance),
                'budget' => $this->budgetTile($user, $budgets),
                'planning' => [
                    'independence' => $independence,
                    'savingCapacity' => $savingCapacity,
                ],
            ],
            'score' => $this->getDailyScore->execute($user, $year, $month),
            'balance' => $balance,
            'monthlyFlows' => $this->monthlyFlows($user, $monthStart),
            'budgets' => $budgets,
            'topPosts' => $this->topPosts($user, $monthStart),
            'savingCapacity' => $savingCapacity,
            'independence' => $independence,
        ];
    }

    /**
     * The day the scores are asked for: today on the current month, else the
     * month's last day (past) or first day (future) — always a day inside the
     * month shown, so the month score is that month's.
     */
    private function scoresDate(User $user, int $year, int $month): \DateTimeImmutable
    {
        $today = $this->getFinanceScores->resolveDate($user, null);
        $shown = new \DateTimeImmutable(sprintf('%04d-%02d-01', $year, $month));
        $current = $today->modify('first day of this month');

        return match (true) {
            $shown == $current => $today,
            $shown < $current => $shown->modify('last day of this month'),
            default => $shown,
        };
    }

    /**
     * @param array<string, mixed> $balance
     *
     * @return array<string, mixed>
     */
    private function accountsTile(array $balance): array
    {
        return [
            'totalCents' => $balance['totalCents'],
            'cushionCents' => $balance['cushionCents'],
            'availableCents' => $balance['availableCents'],
            'accounts' => array_map(static fn (array $account): array => [
                'id' => $account['id'],
                'name' => $account['name'],
                'bank' => $account['bank'],
                'balanceCents' => $account['balanceCents'],
                'currency' => $account['currency'],
                'isCushion' => $account['isCushion'],
            ], $balance['accounts']),
        ];
    }

    /**
     * The envelopes of the month (the roots: a daughter is inside her mother),
     * the safety cushion, the reference salary and the balance the eve of the
     * salary. The last two are null when nobody knows them — never a 0 €.
     *
     * @param list<array<string, mixed>> $budgets
     *
     * @return array<string, mixed>
     */
    private function budgetTile(User $user, array $budgets): array
    {
        $envelopes = [];
        foreach ($budgets as $line) {
            if (null !== $line['parentEnvelopeId']) {
                continue;
            }

            $envelopes[] = [
                'envelopeId' => $line['id'],
                'name' => $line['categoryName'],
                'budgetCents' => $line['amountCents'],
                'consumedCents' => $line['consumedCents'],
                'isOverspent' => $line['isOverspent'],
                'isOvercommitted' => $line['isOvercommitted'],
            ];
        }

        $cushion = $this->getCushionStatus->execute($user);
        $reference = $this->getReferenceSalary->execute($user);
        $outlook = $this->getTreasuryOutlook->execute($user);

        return [
            'envelopes' => $envelopes,
            'cushion' => [
                'state' => $cushion['state'],
                'targetCents' => $cushion['targetCents'],
                'currentCents' => $cushion['currentCents'],
                'coveragePercent' => $cushion['coveragePercent'],
                'monthsCovered' => $cushion['monthsCovered'],
                'isConfigured' => $cushion['isConfigured'],
            ],
            'expectedSalary' => null === $reference['monthlyCents'] ? null : [
                'monthlyCents' => $reference['monthlyCents'],
                'source' => $reference['source'],
                'expectedOn' => $outlook['salary']['expectedOn'],
            ],
            'balanceBeforeSalary' => $outlook['balanceBeforeSalary'],
        ];
    }

    /**
     * @param Account[] $accounts
     *
     * @return array<string, mixed>
     */
    private function balance(array $accounts): array
    {
        $total = 0;
        $cushion = 0;
        $byAccount = [];

        foreach ($accounts as $account) {
            $total += $account->getBalanceCents();
            if ($account->isCushion()) {
                $cushion += $account->getBalanceCents();
            }

            $byAccount[] = [
                'id' => (string) $account->getId(),
                'name' => $account->getName(),
                'bank' => $account->effectiveBank(),
                'type' => $account->getType()->value,
                'balanceCents' => $account->getBalanceCents(),
                'currency' => $account->getCurrency(),
                'isCushion' => $account->isCushion(),
            ];
        }

        return [
            'totalCents' => $total,
            'cushionCents' => $cushion,
            // What is not set aside as the safety net.
            'availableCents' => $total - $cushion,
            'accounts' => $byAccount,
        ];
    }

    /**
     * Twelve rolling months ending with the one shown. Months without a
     * movement are present at zero, so the series never has holes.
     *
     * @return array<int, array{month: string, incomeCents: int, expenseCents: int, netCents: int}>
     */
    private function monthlyFlows(User $user, \DateTimeImmutable $monthStart): array
    {
        $from = $monthStart->modify(sprintf('-%d months', self::TREND_MONTHS - 1));
        $flows = $this->transactionRepository->sumMonthlyFlowsBetween(
            $user,
            $from,
            $monthStart->modify('+1 month'),
        );

        $series = [];
        for ($offset = 0; $offset < self::TREND_MONTHS; ++$offset) {
            $key = $from->modify(sprintf('+%d months', $offset))->format('Y-m');
            $income = $flows[$key]['incomeCents'] ?? 0;
            $expense = $flows[$key]['expenseCents'] ?? 0;

            $series[] = [
                'month' => $key,
                'incomeCents' => $income,
                'expenseCents' => $expense,
                'netCents' => $income - $expense,
            ];
        }

        return $series;
    }

    /**
     * The biggest spending posts of the month, each against the same post last
     * month — a post that only exists this month shows its whole amount as the
     * change.
     *
     * @return array<int, array<string, mixed>>
     */
    private function topPosts(User $user, \DateTimeImmutable $monthStart): array
    {
        $previousStart = $monthStart->modify('-1 month');

        $current = $this->transactionRepository->sumSpendingByCategoryBetween(
            $user,
            $monthStart,
            $monthStart->modify('+1 month'),
        );
        $previous = $this->transactionRepository->sumSpendingByCategoryBetween(
            $user,
            $previousStart,
            $monthStart,
        );

        $previousByCategory = [];
        foreach ($previous as $row) {
            $previousByCategory[$row['categoryId'] ?? ''] = $row['spentCents'];
        }

        $posts = [];
        foreach (\array_slice($current, 0, self::TOP_POSTS) as $row) {
            $previousCents = $previousByCategory[$row['categoryId'] ?? ''] ?? 0;

            $posts[] = $row + [
                'previousMonthCents' => $previousCents,
                'changeCents' => $row['spentCents'] - $previousCents,
            ];
        }

        return $posts;
    }
}
