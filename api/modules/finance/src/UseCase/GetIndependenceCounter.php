<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\LoanRepository;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * How far the rentes already cover the way the user lives — the one figure
 * Maggie Finance is built around (M9).
 *
 * Both sides of the ratio are **measured**, never declared, and over the same
 * window: the rentes are the credits of the categories flagged as such, the
 * train de vie is what a month actually costs. A declared rente against a
 * measured lifestyle would compare two different things, and the percentage
 * would move when nothing in the user's life did.
 *
 * The target date N and the progression curve are Premium (v1.1): nothing is
 * left here for them, because an empty date reads as a promise.
 */
class GetIndependenceCounter
{
    /** The steps the user is walking towards 100 %. */
    private const MILESTONES = [25, 50, 75, 100];

    public function __construct(
        private readonly MeasureMonthlyLifestyle $measureMonthlyLifestyle,
        private readonly TransactionRepository $transactionRepository,
        private readonly CategoryRepository $categoryRepository,
        private readonly LoanRepository $loanRepository,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(User $user, ?\DateTimeImmutable $thisMonth = null): array
    {
        $thisMonth ??= new \DateTimeImmutable('first day of this month');
        [$from, $until] = MeasureMonthlyLifestyle::sampleWindow($thisMonth);

        $lifestyleCents = $this->measureMonthlyLifestyle->execute($user, $thisMonth);
        $byCategory = $this->transactionRepository->sumPassiveIncomeByCategoryBetween($user, $from, $until);

        $passiveIncomeCents = intdiv(
            array_sum(array_column($byCategory, 'incomeCents')),
            MeasureMonthlyLifestyle::SAMPLE_MONTHS,
        );

        $loanPaymentsCents = array_sum(array_map(
            static fn (Loan $loan) => $loan->getMonthlyPaymentCents(),
            $this->loanRepository->findByUser($user),
        ));

        // Not capped: above 100 % the rentes cover more than the month costs,
        // and that is precisely what the user is after.
        $coveragePercent = $lifestyleCents > 0
            ? (int) round($passiveIncomeCents / $lifestyleCents * 100)
            : 0;

        $monthlyNeedCents = $lifestyleCents + $loanPaymentsCents;
        $milestones = $this->milestones($lifestyleCents, $passiveIncomeCents);
        $next = $lifestyleCents > 0
            ? $this->nextMilestone($milestones, $passiveIncomeCents)
            : ['percent' => null, 'gapCents' => null];

        return [
            'coveragePercent' => $coveragePercent,
            'lifestyleCents' => $lifestyleCents,
            'passiveIncomeCents' => $passiveIncomeCents,
            'gapCents' => max(0, $lifestyleCents - $passiveIncomeCents),
            'sampleMonths' => MeasureMonthlyLifestyle::SAMPLE_MONTHS,
            // Without a measured month there is no denominator, so there is no
            // percentage either — and saying 0 % would be a verdict.
            'isMeasurable' => $lifestyleCents > 0,
            'hasPassiveIncomeCategories' => [] !== $this->categoryRepository->findPassiveIncomeByUser($user),
            'isReached' => $lifestyleCents > 0 && $passiveIncomeCents >= $lifestyleCents,
            // While a loan runs, the month costs more than the train de vie:
            // same ratio, payments included.
            'loanPaymentsCents' => $loanPaymentsCents,
            'monthlyNeedCents' => $monthlyNeedCents,
            'coverageWithDebtPercent' => $monthlyNeedCents > 0
                ? (int) round($passiveIncomeCents / $monthlyNeedCents * 100)
                : 0,
            'nextMilestonePercent' => $next['percent'],
            'nextMilestoneGapCents' => $next['gapCents'],
            'milestones' => $milestones,
            'byCategory' => array_map(static function (array $row) use ($passiveIncomeCents) {
                $monthlyCents = intdiv($row['incomeCents'], MeasureMonthlyLifestyle::SAMPLE_MONTHS);

                return [
                    'categoryId' => $row['categoryId'],
                    'categoryName' => $row['categoryName'],
                    'monthlyCents' => $monthlyCents,
                    'sharePercent' => $passiveIncomeCents > 0
                        ? (int) round($monthlyCents / $passiveIncomeCents * 100)
                        : 0,
                ];
            }, $byCategory),
        ];
    }

    /**
     * Each step, whether it is behind, and the monthly rente it takes to stand
     * on it. An amount, not a date: the date is Premium.
     *
     * @return array<int, array{percent: int, isReached: bool, monthlyIncomeNeededCents: int}>
     */
    private function milestones(int $lifestyleCents, int $passiveIncomeCents): array
    {
        return array_map(static function (int $percent) use ($lifestyleCents, $passiveIncomeCents) {
            $needed = (int) ceil($lifestyleCents * $percent / 100);

            return [
                'percent' => $percent,
                'isReached' => $lifestyleCents > 0 && $passiveIncomeCents >= $needed,
                'monthlyIncomeNeededCents' => $needed,
            ];
        }, self::MILESTONES);
    }

    /**
     * The first step still ahead, and what is missing to reach it. Both null
     * once 100 % is behind: there is nothing left to aim at.
     *
     * @param array<int, array{percent: int, isReached: bool, monthlyIncomeNeededCents: int}> $milestones
     *
     * @return array{percent: ?int, gapCents: ?int}
     */
    private function nextMilestone(array $milestones, int $passiveIncomeCents): array
    {
        foreach ($milestones as $milestone) {
            if (!$milestone['isReached']) {
                return [
                    'percent' => $milestone['percent'],
                    'gapCents' => max(0, $milestone['monthlyIncomeNeededCents'] - $passiveIncomeCents),
                ];
            }
        }

        return ['percent' => null, 'gapCents' => null];
    }
}
