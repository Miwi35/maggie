<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Enum\DailyScore;
use Maggie\Finance\Enum\ObligationFlag;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * The daily signal: green, neutral, orange or red, always with the reasons
 * behind it. A signal without its cause reads as a verdict; with its cause,
 * it is information.
 */
class GetDailyScore
{
    public function __construct(
        private readonly GetBudgetStatus $getBudgetStatus,
        private readonly GetCushionStatus $getCushionStatus,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(User $user, int $year, int $month): array
    {
        $budget = $this->getBudgetStatus->execute($user, $year, $month);
        $cushion = $this->getCushionStatus->execute($user);
        $comparison = $this->compareWithLastYear($user, $year, $month);

        $reasons = [];
        $overspentMandatory = [];
        $overspentOptional = [];
        $overcommitted = [];

        foreach ($budget['budgets'] as $line) {
            if ($line['isOverspent']) {
                $isMandatory = $line['categoryObligation'] === ObligationFlag::Mandatory->value;
                if ($isMandatory) {
                    $overspentMandatory[] = $line;
                } else {
                    $overspentOptional[] = $line;
                }

                $reasons[] = [
                    'code' => $isMandatory ? 'mandatory_category_exceeded' : 'optional_category_exceeded',
                    'categoryName' => $line['categoryName'],
                    'amountCents' => -$line['remainingCents'],
                ];
                continue;
            }

            if ($line['isOvercommitted']) {
                $overcommitted[] = $line;
                $reasons[] = [
                    'code' => 'plans_exceed_category_budget',
                    'categoryName' => $line['categoryName'],
                    'amountCents' => -$line['availableCents'],
                ];
            }
        }

        $totalOverspent = $budget['totalBudgetedCents'] > 0
            && $budget['totalConsumedCents'] > $budget['totalBudgetedCents'];

        if ($totalOverspent) {
            $reasons[] = [
                'code' => 'total_budget_exceeded',
                'amountCents' => $budget['totalConsumedCents'] - $budget['totalBudgetedCents'],
            ];
        }

        if ($cushion['blocksGreenScore']) {
            $reasons[] = [
                'code' => 'cushion_incomplete',
                'amountCents' => $cushion['deficitCents'],
            ];
        }

        $reasons[] = [
            'code' => $comparison['isBetter'] ? 'below_last_year' : 'above_last_year',
            'amountCents' => abs($comparison['differenceCents']),
        ];

        $score = $this->decide(
            hasBudget: [] !== $budget['budgets'],
            totalOverspent: $totalOverspent,
            overspentMandatory: [] !== $overspentMandatory,
            overspentOptional: [] !== $overspentOptional,
            overcommitted: [] !== $overcommitted,
            cushionBlocksGreen: $cushion['blocksGreenScore'],
            betterThanLastYear: $comparison['isBetter'],
        );

        if ([] === $budget['budgets']) {
            $reasons = [['code' => 'no_budget']];
        }

        return [
            'score' => $score->value,
            'year' => $year,
            'month' => $month,
            'reasons' => $reasons,
            'budget' => [
                'totalBudgetedCents' => $budget['totalBudgetedCents'],
                'totalConsumedCents' => $budget['totalConsumedCents'],
                'totalPlannedCents' => $budget['totalPlannedCents'],
                'totalAvailableCents' => $budget['totalAvailableCents'],
                'overspentCategories' => array_map(
                    static fn (array $line) => $line['categoryName'],
                    [...$overspentMandatory, ...$overspentOptional],
                ),
            ],
            'cushion' => [
                'state' => $cushion['state'],
                'blocksGreenScore' => $cushion['blocksGreenScore'],
            ],
            'comparison' => $comparison,
        ];
    }

    /**
     * Red and orange are about the budget alone. The cushion can only hold
     * green back — building one is a normal state, not a fault — and so can
     * spending more than last year, which is often perfectly legitimate.
     */
    private function decide(
        bool $hasBudget,
        bool $totalOverspent,
        bool $overspentMandatory,
        bool $overspentOptional,
        bool $overcommitted,
        bool $cushionBlocksGreen,
        bool $betterThanLastYear,
    ): DailyScore {
        if (!$hasBudget) {
            return DailyScore::Neutral;
        }

        if ($totalOverspent || $overspentMandatory) {
            return DailyScore::Red;
        }

        if ($overspentOptional || $overcommitted) {
            return DailyScore::Orange;
        }

        if (!$cushionBlocksGreen && $betterThanLastYear) {
            return DailyScore::Green;
        }

        return DailyScore::Neutral;
    }

    /** @return array{thisMonthCents: int, sameMonthLastYearCents: int, differenceCents: int, isBetter: bool} */
    private function compareWithLastYear(User $user, int $year, int $month): array
    {
        $start = new \DateTimeImmutable(sprintf('%04d-%02d-01', $year, $month));
        $lastYearStart = $start->modify('-1 year');

        $thisMonth = $this->transactionRepository->sumConsumedBetween(
            $user,
            $start,
            $start->modify('+1 month'),
        );
        $lastYear = $this->transactionRepository->sumConsumedBetween(
            $user,
            $lastYearStart,
            $lastYearStart->modify('+1 month'),
        );

        return [
            'thisMonthCents' => $thisMonth,
            'sameMonthLastYearCents' => $lastYear,
            'differenceCents' => $thisMonth - $lastYear,
            // With nothing to compare against, last year cannot vouch for this one.
            'isBetter' => $lastYear > 0 && $thisMonth < $lastYear,
        ];
    }
}
