<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\LoanRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\IndependenceProjector;

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
 * `coveragePercent` stays on the real. Next to it, `projectedCoveragePercent`
 * says where the rentes stand "at this rhythm" (MAG-307): the same
 * lifestyle, against the passive income of the {@see MeasureMonthlyLifestyle::SAMPLE_MONTHS}
 * months ahead — what lands (as `passiveIncomeCents` counts it) plus what the
 * owner's confirmed series still expect, never an occurrence already settled
 * by a line. A series of expenses moves neither figure.
 *
 * `projection` (MAG-549) dates N, draws the coverage curve and dates the
 * milestones from the current planning: what the planning leaves of the
 * capacity each month is invested at {@see IndependenceProjector::YIELD_BPS}.
 * Beyond the planning's horizon the last month's rhythm goes on, without the
 * projects not yet bought. N is null when it is not reached within 20 years.
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
        private readonly GetRecurringOccurrences $getRecurringOccurrences,
        private readonly GetProjectPlanning $getProjectPlanning,
        private readonly IndependenceProjector $projector,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(User $user, ?\DateTimeImmutable $thisMonth = null): array
    {
        // Midnight: `bookedAt` is a date, and the current time of day would
        // slide the window by a day at both ends (see `sampleWindow`).
        $thisMonth ??= new \DateTimeImmutable('midnight first day of this month');
        [$from, $until] = MeasureMonthlyLifestyle::sampleWindow($thisMonth);

        $lifestyleCents = $this->measureMonthlyLifestyle->execute($user, $thisMonth);
        $byCategory = $this->transactionRepository->sumPassiveIncomeByCategoryBetween($user, $from, $until);

        $passiveIncomeCents = intdiv(
            array_sum(array_column($byCategory, 'incomeCents')),
            MeasureMonthlyLifestyle::SAMPLE_MONTHS,
        );

        $loanPaymentsCents = array_sum(array_map(
            static fn (Loan $loan) => $loan->getMonthlyPaymentCents(),
            $this->loanRepository->findSignedByUser($user),
        ));

        // Truncated, not rounded, so that 100 % means covered and nothing
        // else: a rounded 99,6 % would read « 100 % » next to « il manque
        // 0,04 € ». And not capped either — above 100 the rentes cover more
        // than the month costs, which is what the user is after.
        $coveragePercent = $lifestyleCents > 0
            ? intdiv($passiveIncomeCents * 100, $lifestyleCents)
            : 0;

        $projectedPassiveIncomeCents = $this->projectedPassiveIncome($user, $thisMonth);
        $projectedCoveragePercent = $lifestyleCents > 0
            ? intdiv($projectedPassiveIncomeCents * 100, $lifestyleCents)
            : 0;

        $monthlyNeedCents = $lifestyleCents + $loanPaymentsCents;
        $milestones = $this->milestones($lifestyleCents, $passiveIncomeCents);
        $next = $lifestyleCents > 0
            ? $this->nextMilestone($milestones, $passiveIncomeCents)
            : ['percent' => null, 'gapCents' => null];

        return [
            'coveragePercent' => $coveragePercent,
            'projectedCoveragePercent' => $projectedCoveragePercent,
            'projectedPassiveIncomeCents' => $projectedPassiveIncomeCents,
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
            // Gated on the same measure as the headline: a percentage against
            // the loan payments alone would contradict `isMeasurable`.
            'coverageWithDebtPercent' => $lifestyleCents > 0 && $monthlyNeedCents > 0
                ? intdiv($passiveIncomeCents * 100, $monthlyNeedCents)
                : 0,
            'projection' => $this->projection($user, $passiveIncomeCents, $lifestyleCents),
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

    /** @return array{startMonth: string, nDate: string|null, curve: list<array{month: string, coverage: float}>, milestones: array<int, string|null>} */
    private function projection(User $user, int $passiveIncomeCents, int $lifestyleCents): array
    {
        $plan = $this->getProjectPlanning->execute($user);

        $investable = array_map(
            static fn (array $month): int => max(0, $month['capacityCents'] - $month['salaryCents']),
            $plan['months'],
        );
        $last = $plan['months'][array_key_last($plan['months'])];
        $tail = max(0, $plan['netCapacityCents'] - $last['loanPaymentsCents']);

        return ['startMonth' => $plan['startMonth']] + $this->projector->project(
            new \DateTimeImmutable($plan['startMonth'].'-01'),
            $passiveIncomeCents,
            $lifestyleCents,
            $investable,
            $tail,
        );
    }

    /**
     * The monthly average of the passive income over the months ahead: the
     * credits of the rente categories that land in the window, plus the
     * occurrences of the series filed under them that no line settles yet.
     */
    private function projectedPassiveIncome(User $user, \DateTimeImmutable $thisMonth): int
    {
        $until = $thisMonth->modify(sprintf('+%d months', MeasureMonthlyLifestyle::SAMPLE_MONTHS));

        $landed = array_sum(array_column(
            $this->transactionRepository->sumPassiveIncomeByCategoryBetween($user, $thisMonth, $until),
            'incomeCents',
        ));

        $passive = array_flip(array_map(
            static fn ($category): string => (string) $category->getId(),
            $this->categoryRepository->findPassiveIncomeByUser($user),
        ));
        $expected = 0;
        foreach ($this->getRecurringOccurrences->execute($user, $thisMonth, $until) as $occurrence) {
            if ($occurrence['amountCents'] > 0 && isset($passive[$occurrence['categoryId']])) {
                $expected += $occurrence['amountCents'];
            }
        }

        return intdiv($landed + $expected, MeasureMonthlyLifestyle::SAMPLE_MONTHS);
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
