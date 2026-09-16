<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * Budget consumption of a period: every envelope covering it, with what has
 * already been spent on its category and what is left.
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

        foreach ($this->envelopeRepository->findCoveringPeriod($user, $year, $month) as $envelope) {
            $spentCents = $this->transactionRepository->sumSpentForCategoryBetween(
                $user,
                $envelope->getCategory(),
                $envelope->getPeriodStart(),
                $envelope->getPeriodEnd(),
            );

            $totalBudgeted += $envelope->getAmountCents();
            $totalSpent += $spentCents;

            $budgets[] = [
                'id' => (string) $envelope->getId(),
                'categoryId' => (string) $envelope->getCategory()->getId(),
                'categoryName' => $envelope->getCategory()->getName(),
                'mode' => $envelope->getMode()->value,
                'amountCents' => $envelope->getAmountCents(),
                'currency' => $envelope->getCurrency(),
                'year' => $envelope->getYear(),
                'month' => $envelope->getMonth(),
                'spentCents' => $spentCents,
                'remainingCents' => $envelope->getAmountCents() - $spentCents,
                'isOverspent' => $spentCents > $envelope->getAmountCents(),
            ];
        }

        return [
            'year' => $year,
            'month' => $month,
            'totalBudgetedCents' => $totalBudgeted,
            'totalSpentCents' => $totalSpent,
            'totalRemainingCents' => $totalBudgeted - $totalSpent,
            'budgets' => $budgets,
        ];
    }
}
