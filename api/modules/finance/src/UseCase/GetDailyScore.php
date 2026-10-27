<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Enum\DailyScore;
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
        private readonly GetReferenceSalary $getReferenceSalary,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(User $user, int $year, int $month): array
    {
        $budget = $this->getBudgetStatus->execute($user, $year, $month);
        $cushion = $this->getCushionStatus->execute($user);
        $comparison = $this->compareWithLastYear($user, $year, $month);
        $reference = $this->getReferenceSalary->execute($user);

        $reasons = [];
        $overspentMandatory = [];
        $overspentOptional = [];
        $overcommitted = [];

        foreach ($budget['budgets'] as $line) {
            if ($line['isOverspent']) {
                $isMandatory = $this->isMandatoryOverrun($line);
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

        if ($cushion['blocksGreenScore']) {
            $reasons[] = [
                'code' => 'cushion_incomplete',
                'amountCents' => $cushion['deficitCents'],
            ];
        }

        $aboveReferenceSalary = null !== $reference['monthlyCents']
            && $comparison['thisMonthCents'] > $reference['monthlyCents'];

        if ($aboveReferenceSalary) {
            $reasons[] = [
                'code' => 'spending_above_reference_salary',
                'amountCents' => $comparison['thisMonthCents'] - $reference['monthlyCents'],
            ];
        }

        $reasons[] = [
            'code' => $comparison['isBetter'] ? 'below_last_year' : 'above_last_year',
            'amountCents' => abs($comparison['differenceCents']),
        ];

        $score = $this->decide(
            hasBudget: [] !== $budget['budgets'],
            overspentMandatory: [] !== $overspentMandatory,
            overspentOptional: [] !== $overspentOptional,
            overcommitted: [] !== $overcommitted,
            cushionBlocksGreen: $cushion['blocksGreenScore'],
            aboveReferenceSalary: $aboveReferenceSalary,
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
            'referenceSalary' => [
                'monthlyCents' => $reference['monthlyCents'],
                'source' => $reference['source'],
            ],
        ];
    }

    /**
     * An envelope is over its amount because of an obligation when the
     * transactions qualified as mandatory weigh on it and what is not
     * mandatory would have fitted alone. The envelope's category says nothing:
     * two lines of the same category may be told apart.
     *
     * @param array<string, mixed> $line
     */
    private function isMandatoryOverrun(array $line): bool
    {
        return $line['mandatoryConsumedCents'] > 0
            && $line['consumedCents'] - $line['mandatoryConsumedCents'] <= $line['amountCents'];
    }

    /**
     * Red and orange are about the envelopes alone: each one held or not, never
     * the sum of all spending against their total (MAG-643). The cushion can
     * only hold green back — building one is a normal state, not a fault — and
     * so can spending more than last year, or more than the reference salary,
     * which are often perfectly legitimate.
     */
    private function decide(
        bool $hasBudget,
        bool $overspentMandatory,
        bool $overspentOptional,
        bool $overcommitted,
        bool $cushionBlocksGreen,
        bool $aboveReferenceSalary,
        bool $betterThanLastYear,
    ): DailyScore {
        if (!$hasBudget) {
            return DailyScore::Neutral;
        }

        if ($overspentMandatory) {
            return DailyScore::Red;
        }

        if ($overspentOptional || $overcommitted) {
            return DailyScore::Orange;
        }

        if (!$cushionBlocksGreen && !$aboveReferenceSalary && $betterThanLastYear) {
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
