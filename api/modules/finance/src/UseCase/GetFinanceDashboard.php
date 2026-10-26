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
 * what is to do (lines to sort, rejections to settle, the review, the advance
 * to give back), and the older blocks the agent still reads in one call — what is in the
 * accounts, twelve months of money in and out, the budget gauges, the biggest
 * spending posts against last month, and what is left to save.
 *
 * It composes the use cases that already exist rather than recomputing
 * anything: one answer, one set of rules. A pure read: it writes, publishes
 * and indexes nothing. It is computed by {@see RefreshFinanceDashboard} into
 * the read model the endpoint answers from (MAG-545), and live only when that
 * model does not hold the month asked for or a write has outdated it.
 */
class GetFinanceDashboard
{
    private const TREND_MONTHS = 12;
    private const TOP_POSTS = 5;
    private const FOLLOWING_PROJECTS = 2;

    /**
     * The version of the payload's shape, sent as `shape`: the read model only
     * serves what was stored in the shape this release answers, so a field a
     * deploy adds is there at once, not the next day (MAG-565). Bump it with
     * every key added, removed or renamed — FinanceDashboardProjectionTest
     * fingerprints the recorded payload to remind it.
     */
    public const PAYLOAD_SHAPE = 2;

    public function __construct(
        private readonly GetDailyScore $getDailyScore,
        private readonly GetFinanceScores $getFinanceScores,
        private readonly GetBudgetStatus $getBudgetStatus,
        private readonly GetCushionStatus $getCushionStatus,
        private readonly GetReferenceSalary $getReferenceSalary,
        private readonly GetTreasuryOutlook $getTreasuryOutlook,
        private readonly GetDebtTimeline $getDebtTimeline,
        private readonly GetIndependenceCounter $getIndependenceCounter,
        private readonly GetAccountIncidents $getAccountIncidents,
        private readonly GetSavingsAdvances $getSavingsAdvances,
        private readonly GetMonthlyReview $getMonthlyReview,
        private readonly GetPeerDebtSummary $getPeerDebtSummary,
        private readonly GetProjectPlanning $getProjectPlanning,
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
        $planning = $this->getProjectPlanning->execute($user);
        $savingCapacity = $this->getDebtTimeline->execute($user)['savingCapacity'];
        // Not a figure of the month shown: the counter is measured over the
        // last complete months, like the saving capacity beside it.
        $independence = $this->getIndependenceCounter->execute($user);
        $rejections = $this->rejectionsToSettleByAccount($accounts);

        return [
            'shape' => self::PAYLOAD_SHAPE,
            'year' => $year,
            'month' => $month,
            'scores' => $this->getFinanceScores->execute($user, $this->getFinanceScores->dateInMonth($user, $year, $month)),
            'tiles' => [
                'accounts' => $this->accountsTile($balance, $rejections),
                'budget' => $this->budgetTile($user, $budgets),
                'planning' => [
                    'independence' => $independence,
                    'savingCapacity' => $savingCapacity,
                ],
            ],
            'nextProject' => $this->nextProject($user, $year, $month, $budgets, $planning),
            'projectBeyondHorizon' => $this->projectBeyondHorizon($planning),
            'todo' => $this->todo($user, $rejections),
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
     * The next project of the planning (MAG-529) with the two after it, and the
     * negative feedback: the month's overspending pushes one of the three back.
     * Null with no project, or when none lands inside the 36 months.
     *
     * The overspending is the sum, over the envelopes of the month, of what
     * each one went over its budget. The planning takes it into account for the
     * month under way only, so a month shown that is not that one carries no delay.
     *
     * @param list<array<string, mixed>> $budgets
     * @param array<string, mixed>       $planning
     *
     * @return array<string, mixed>|null
     */
    private function nextProject(User $user, int $year, int $month, array $budgets, array $planning): ?array
    {
        $planned = array_values(array_filter(
            $planning['projects'],
            static fn (array $project): bool => null !== $project['purchaseMonth'],
        ));
        if ([] === $planned) {
            return null;
        }

        $next = $planned[0];
        $following = \array_slice($planned, 1, self::FOLLOWING_PROJECTS);

        return [
            'projectId' => $next['projectId'],
            'name' => $next['name'],
            'amountCents' => $next['amountCents'],
            'gatheredCents' => $next['gatheredCents'],
            'purchaseMonth' => $next['purchaseMonth'],
            'following' => array_map(static fn (array $project): array => [
                'projectId' => $project['projectId'],
                'name' => $project['name'],
                'amountCents' => $project['amountCents'],
                'purchaseMonth' => $project['purchaseMonth'],
            ], $following),
            'delay' => sprintf('%04d-%02d', $year, $month) === $planning['startMonth']
                ? $this->delay($user, [$next, ...$following], $budgets)
                : null,
        ];
    }

    /**
     * The first project of the planning when none lands inside the horizon (the
     * safety cushion is still being built): the Dashboard hides its block then,
     * the Accueil card still names it. Null with no project, or with one planned.
     *
     * @param array<string, mixed> $planning
     *
     * @return array<string, mixed>|null
     */
    private function projectBeyondHorizon(array $planning): ?array
    {
        $projects = $planning['projects'];
        foreach ($projects as $project) {
            if (null !== $project['purchaseMonth']) {
                return null;
            }
        }
        if ([] === $projects) {
            return null;
        }

        return [
            'projectId' => $projects[0]['projectId'],
            'name' => $projects[0]['name'],
            'amountCents' => $projects[0]['amountCents'],
            'gatheredCents' => $projects[0]['gatheredCents'],
            'horizonMonths' => $planning['horizonMonths'],
        ];
    }

    /**
     * The first of the projects whose purchase month moves back once the
     * month's overspending is counted. A project the overspending pushes out of
     * the horizon has no new month to name: it is left out.
     *
     * @param list<array<string, mixed>> $projects
     * @param list<array<string, mixed>> $budgets
     *
     * @return array<string, mixed>|null
     */
    private function delay(User $user, array $projects, array $budgets): ?array
    {
        $overspent = [];
        foreach ($budgets as $line) {
            if (null !== $line['parentEnvelopeId']) {
                continue;
            }
            $over = $line['consumedCents'] - $line['amountCents'];
            if ($over > 0) {
                $overspent[$line['categoryName']] = $over;
            }
        }
        if ([] === $overspent) {
            return null;
        }

        $overspentCents = array_sum($overspent);
        $pushed = array_column($this->getProjectPlanning->execute($user, $overspentCents)['projects'], null, 'projectId');

        foreach ($projects as $project) {
            $newMonth = $pushed[$project['projectId']]['purchaseMonth'] ?? null;
            if (null === $newMonth) {
                continue;
            }
            $months = $this->monthIndex($newMonth) - $this->monthIndex($project['purchaseMonth']);
            if ($months > 0) {
                return [
                    'projectId' => $project['projectId'],
                    'name' => $project['name'],
                    'months' => $months,
                    'overspentCents' => $overspentCents,
                    'envelopes' => array_keys($overspent),
                    'newPurchaseMonth' => $newMonth,
                ];
            }
        }

        return null;
    }

    private function monthIndex(string $month): int
    {
        return (int) substr($month, 0, 4) * 12 + (int) substr($month, 5, 2);
    }

    /**
     * What waits for the owner, each count the one the screen it opens shows:
     * the pile of the lines to sort, the rejections not settled yet, the
     * review of the month just ended (not done while a line of it is unrated)
     * and what is still owed back to the savings, plus what people owe the owner
     * and what the owner owes them (the « On te doit / Tu dois » pill). Never
     * the month shown: it is what is to do today.
     *
     * @param array<string, int> $rejections the rejections to settle, by account id
     *
     * @return array{toSortCount: int, rejectionsToSettleCount: int, reviewReady: bool, advanceToRepayCents: int, owedToMeCents: int, iOweCents: int}
     */
    private function todo(User $user, array $rejections): array
    {
        $lastMonth = $this->getFinanceScores->resolveDate($user, null)->modify('first day of last month');
        $review = $this->getMonthlyReview->execute($user, (int) $lastMonth->format('Y'), (int) $lastMonth->format('n'));
        $peers = $this->getPeerDebtSummary->execute($user);

        return [
            'toSortCount' => $this->transactionRepository->countToSortForUser($user),
            'rejectionsToSettleCount' => array_sum($rejections),
            'reviewReady' => $review['pendingCount'] > 0,
            'advanceToRepayCents' => $this->getSavingsAdvances->execute($user)['totalOutstandingCents'],
            'owedToMeCents' => $peers['owedToMeCents'],
            'iOweCents' => $peers['iOweCents'],
        ];
    }

    /**
     * The rejections still on the pile of each account's incidents.
     *
     * @param Account[] $accounts
     *
     * @return array<string, int>
     */
    private function rejectionsToSettleByAccount(array $accounts): array
    {
        $byAccount = [];
        foreach ($accounts as $account) {
            $byAccount[(string) $account->getId()] = \count($this->getAccountIncidents->execute($account));
        }

        return $byAccount;
    }

    /**
     * @param array<string, mixed> $balance
     * @param array<string, int>   $rejections
     *
     * @return array<string, mixed>
     */
    private function accountsTile(array $balance, array $rejections): array
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
                // Where the « N rejets » pill goes: the account whose incidents are not empty.
                'rejectionsToSettleCount' => $rejections[$account['id']] ?? 0,
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
