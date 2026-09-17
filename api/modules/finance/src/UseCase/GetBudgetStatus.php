<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * Budget consumption of a period: every envelope covering it, with what the
 * transactions of its category weigh on it.
 *
 * The status decides the weight — spent and committed are money already out
 * (consumed), planned is set aside (reserved), to-arbitrate counts for nothing
 * but is reported so its impact can be weighed before deciding.
 */
class GetBudgetStatus
{
    public function __construct(
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(User $user, int $year, int $month): array
    {
        $budgets = [];
        $totalBudgeted = 0;
        $totalSpent = 0;
        $totalCommitted = 0;
        $totalPlanned = 0;

        foreach ($this->envelopeRepository->findCoveringPeriod($user, $year, $month) as $envelope) {
            $byStatus = $this->transactionRepository->sumByStatusForCategoryBetween(
                $user,
                $envelope->getCategory(),
                $envelope->getPeriodStart(),
                $envelope->getPeriodEnd(),
            );

            $spentCents = $byStatus[TransactionStatus::Spent->value];
            $committedCents = $byStatus[TransactionStatus::Committed->value];
            $plannedCents = $byStatus[TransactionStatus::Planned->value];
            $toArbitrateCents = $byStatus[TransactionStatus::ToArbitrate->value];

            $consumedCents = $spentCents + $committedCents;
            $amountCents = $envelope->getAmountCents();

            $totalBudgeted += $amountCents;
            $totalSpent += $spentCents;
            $totalCommitted += $committedCents;
            $totalPlanned += $plannedCents;

            $budgets[] = [
                'id' => (string) $envelope->getId(),
                'categoryId' => (string) $envelope->getCategory()->getId(),
                'categoryName' => $envelope->getCategory()->getName(),
                'categoryObligation' => $envelope->getCategory()->getObligation()->value,
                'mode' => $envelope->getMode()->value,
                'amountCents' => $amountCents,
                'currency' => $envelope->getCurrency(),
                'year' => $envelope->getYear(),
                'month' => $envelope->getMonth(),
                'spentCents' => $spentCents,
                'committedCents' => $committedCents,
                'plannedCents' => $plannedCents,
                'toArbitrateCents' => $toArbitrateCents,
                // Money already gone, and what is left after it.
                'consumedCents' => $consumedCents,
                'remainingCents' => $amountCents - $consumedCents,
                // What is actually free to spend once plans are set aside.
                'availableCents' => $amountCents - $consumedCents - $plannedCents,
                'isOverspent' => $consumedCents > $amountCents,
                'isOvercommitted' => $consumedCents + $plannedCents > $amountCents,
            ];
        }

        $totalConsumed = $totalSpent + $totalCommitted;

        return [
            'year' => $year,
            'month' => $month,
            'totalBudgetedCents' => $totalBudgeted,
            'totalSpentCents' => $totalSpent,
            'totalCommittedCents' => $totalCommitted,
            'totalPlannedCents' => $totalPlanned,
            'totalConsumedCents' => $totalConsumed,
            'totalRemainingCents' => $totalBudgeted - $totalConsumed,
            'totalAvailableCents' => $totalBudgeted - $totalConsumed - $totalPlanned,
            'budgets' => $budgets,
        ];
    }
}
