<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * The bilan of a quarter, a semester or a year, read by the augmented reviews
 * (MAG-462) in the figures of the monthly balance: month by month what was
 * budgeted, spent and put aside (the curves), each envelope over the whole
 * period (the rings), the satisfaction on what was judged, the savings against
 * what was possible, and the planned expenses done or postponed.
 *
 * Nothing is stored; every part composes the use cases that already answer it.
 * « Projects » are the planned expenses of the period until the Project entity
 * exists: done = committed, postponed = still planned (or to arbitrate) though
 * their month has passed.
 */
class GetPeriodBalance
{
    public function __construct(
        private readonly GetBudgetStatus $getBudgetStatus,
        private readonly GetMonthlyReview $getMonthlyReview,
        private readonly GetDebtTimeline $getDebtTimeline,
        private readonly GetFinanceScores $getFinanceScores,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(User $user, PlanningPeriod $period): array
    {
        $months = [];
        $envelopes = [];
        $keptCents = 0;
        $avoidableCents = 0;

        foreach ($period->monthStarts() as $start) {
            $next = $start->modify('+1 month');
            $budget = $this->getBudgetStatus->execute($user, (int) $start->format('Y'), (int) $start->format('n'));

            $months[] = [
                'month' => $start->format('Y-m'),
                'budgetCents' => $budget['totalBudgetedCents'],
                'spentCents' => $this->transactionRepository->sumConsumedBetween($user, $start, $next),
                'savedCents' => $this->transactionRepository->sumSavedBetween($user, $start, $next),
            ];

            foreach ($budget['budgets'] as $line) {
                if (null !== $line['parentEnvelopeId']) {
                    continue;
                }

                $envelopes[$line['categoryId']] ??= [
                    'categoryId' => $line['categoryId'],
                    'name' => $line['categoryName'],
                    'budgetCents' => 0,
                    'consumedCents' => 0,
                    'alertThresholdPercent' => $line['alertThresholdPercent'],
                ];
                $envelopes[$line['categoryId']]['budgetCents'] += $line['amountCents'];
                $envelopes[$line['categoryId']]['consumedCents'] += $line['consumedCents'];
            }

            $review = $this->getMonthlyReview->execute($user, (int) $start->format('Y'), (int) $start->format('n'));
            $keptCents += $review['keptCents'];
            $avoidableCents += $review['avoidableCents'];
        }

        $savedCents = array_sum(array_column($months, 'savedCents'));
        $capacity = $this->getDebtTimeline->execute($user)['savingCapacity'];
        $judgedCents = $keptCents + $avoidableCents;

        [$done, $postponed] = $this->projects($user, $period);

        return [
            'period' => $period->describe(),
            'months' => $months,
            'totals' => [
                'budgetCents' => array_sum(array_column($months, 'budgetCents')),
                'spentCents' => array_sum(array_column($months, 'spentCents')),
                'savedCents' => $savedCents,
            ],
            'envelopes' => array_values(array_map($this->envelope(...), $envelopes)),
            'satisfaction' => [
                'judgedCents' => $judgedCents,
                'keptCents' => $keptCents,
                'avoidableCents' => $avoidableCents,
                'keptPercent' => $judgedCents > 0 ? (int) round($keptCents * 100 / $judgedCents) : null,
            ],
            'savings' => [
                'savedCents' => $savedCents,
                // Null when no income is known: nothing to compare with, never a 0 €.
                'plannedCents' => $capacity['isIncomeKnown'] ? max(0, $capacity['netCapacityCents']) * $period->months() : null,
            ],
            'projects' => ['done' => $done, 'postponed' => $postponed],
        ];
    }

    /**
     * @param array<string, mixed> $envelope
     *
     * @return array<string, mixed>
     */
    private function envelope(array $envelope): array
    {
        $budgetCents = $envelope['budgetCents'];
        $consumedCents = $envelope['consumedCents'];

        $status = match (true) {
            $consumedCents > $budgetCents => 'over',
            $budgetCents > 0 && $consumedCents * 100 >= $envelope['alertThresholdPercent'] * $budgetCents => 'close',
            default => 'held',
        };

        return [
            'categoryId' => $envelope['categoryId'],
            'name' => $envelope['name'],
            'budgetCents' => $budgetCents,
            'consumedCents' => $consumedCents,
            'percent' => $budgetCents > 0 ? intdiv($consumedCents * 100, $budgetCents) : null,
            'status' => $status,
        ];
    }

    /** @return array{list<array<string, mixed>>, list<array<string, mixed>>} */
    private function projects(User $user, PlanningPeriod $period): array
    {
        $today = $this->getFinanceScores->resolveDate($user, null);
        $done = [];
        $postponed = [];

        foreach ($this->transactionRepository->findPlansBetween($user, $period->start(), $period->end()) as $transaction) {
            if (TransactionStatus::Committed === $transaction->getStatus()) {
                $done[] = $this->event($transaction);
            } elseif ($transaction->getBookedAt() < $today->modify('first day of this month')) {
                $postponed[] = $this->event($transaction);
            }
        }

        return [$done, $postponed];
    }

    /** @return array<string, mixed> */
    private function event(Transaction $transaction): array
    {
        return [
            'id' => (string) $transaction->getId(),
            'label' => $transaction->getLabel(),
            'amountCents' => abs($transaction->getOwnAmountCents()),
            'currency' => $transaction->getCurrency(),
            'month' => $transaction->getBookedAt()->format('Y-m'),
            'status' => $transaction->getStatus()->value,
            'categoryName' => $transaction->getCategory()?->getName(),
        ];
    }
}
