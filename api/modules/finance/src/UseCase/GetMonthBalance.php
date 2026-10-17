<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * The balance of one month, in the figures the « État courant » dashboard
 * draws: the month's spending against its budget and its rhythm, each root
 * envelope held / close / over, the satisfaction on what was judged, what was
 * put aside against what was planned, the safety cushion, and what is still
 * waiting to be qualified.
 *
 * It composes the use cases that already answer each part — one set of rules.
 * Envelopes and the rhythm read the qualified transactions only (a line with
 * no category belongs to no envelope); the unqualified ones of the month are
 * reported apart under `toQualify`. A pure read: it publishes nothing and
 * indexes nothing (the one write is the default cushion row GetCushionStatus
 * creates for a user who has none).
 */
class GetMonthBalance
{
    public function __construct(
        private readonly GetFinanceScores $getFinanceScores,
        private readonly GetBudgetStatus $getBudgetStatus,
        private readonly GetMonthlyReview $getMonthlyReview,
        private readonly GetCushionStatus $getCushionStatus,
        private readonly GetDebtTimeline $getDebtTimeline,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(User $user, \DateTimeImmutable $monthStart): array
    {
        $year = (int) $monthStart->format('Y');
        $month = (int) $monthStart->format('n');
        $next = $monthStart->modify('+1 month');

        $today = $this->getFinanceScores->resolveDate($user, null);
        $scores = $this->getFinanceScores->execute($user, $this->getFinanceScores->dateInMonth($user, $year, $month));
        $budgets = $this->getBudgetStatus->execute($user, $year, $month)['budgets'];
        $review = $this->getMonthlyReview->execute($user, $year, $month);
        $cushion = $this->getCushionStatus->execute($user);
        $capacity = $this->getDebtTimeline->execute($user)['savingCapacity'];
        $toQualify = $this->transactionRepository->summariseToSortBetween($user, $monthStart, $next);

        return [
            'month' => $monthStart->format('Y-m'),
            'isCurrentMonth' => $today->format('Y-m') === $monthStart->format('Y-m'),
            'spending' => $scores['month'],
            'envelopes' => $this->envelopes($budgets),
            'satisfaction' => [
                'judgedCents' => $review['keptCents'] + $review['avoidableCents'],
                'keptCents' => $review['keptCents'],
                // The superfluous amount: what was judged avoidable.
                'avoidableCents' => $review['avoidableCents'],
                'unratedCount' => $review['pendingCount'],
                // Share, by amount, of what was judged that is worth keeping; null while nothing is judged.
                'keptPercent' => $review['optimisationScore'],
            ],
            'savings' => [
                'savedCents' => $this->transactionRepository->sumSavedBetween($user, $monthStart, $next),
                // Null when no income is known: there is nothing to plan against, never a 0 €.
                'plannedCents' => $capacity['isIncomeKnown'] ? max(0, $capacity['netCapacityCents']) : null,
            ],
            'cushion' => [
                'state' => $cushion['state'],
                'targetCents' => $cushion['targetCents'],
                'currentCents' => $cushion['currentCents'],
                'coveragePercent' => $cushion['coveragePercent'],
                'monthsCovered' => $cushion['monthsCovered'],
                'isConfigured' => $cushion['isConfigured'],
            ],
            'toQualify' => $toQualify,
        ];
    }

    /**
     * The root envelopes (a daughter is inside her mother): `over` once the
     * budget is exceeded, `close` from the envelope's alert threshold, else
     * `held`.
     *
     * @param list<array<string, mixed>> $budgets
     *
     * @return list<array<string, mixed>>
     */
    private function envelopes(array $budgets): array
    {
        $envelopes = [];
        foreach ($budgets as $line) {
            if (null !== $line['parentEnvelopeId']) {
                continue;
            }

            $budgetCents = $line['amountCents'];
            $consumedCents = $line['consumedCents'];

            $status = match (true) {
                $line['isOverspent'] => 'over',
                $budgetCents > 0 && $consumedCents * 100 >= $line['alertThresholdPercent'] * $budgetCents => 'close',
                default => 'held',
            };

            $envelopes[] = [
                'envelopeId' => $line['id'],
                'name' => $line['categoryName'],
                'budgetCents' => $budgetCents,
                'consumedCents' => $consumedCents,
                'percent' => $budgetCents > 0 ? intdiv($consumedCents * 100, $budgetCents) : null,
                'status' => $status,
            ];
        }

        return $envelopes;
    }
}
