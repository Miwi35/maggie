<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Repository\LoanRepository;
use Maggie\Finance\Service\LoanSchedule;

/**
 * When each fixed charge falls away, and what is actually left to save.
 *
 * Each loan's schedule comes from LoanSchedule, by its type. Projected loans
 * (MAG-522) are left out: nothing is signed yet.
 */
class GetDebtTimeline
{
    private const DEFAULT_HORIZON_MONTHS = 60;

    public function __construct(
        private readonly LoanRepository $loanRepository,
        private readonly MeasureMonthlyLifestyle $measureMonthlyLifestyle,
        private readonly GetReferenceSalary $getReferenceSalary,
        private readonly LoanSchedule $loanSchedule,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(User $user, ?int $horizonMonths = null): array
    {
        $horizon = $horizonMonths ?? self::DEFAULT_HORIZON_MONTHS;
        $loans = $this->loanRepository->findSignedByUser($user);
        $today = new \DateTimeImmutable('first day of this month');

        $schedules = [];
        $totalPrincipal = 0;
        $totalMonthly = 0;
        $totalInterest = 0;
        $totalInsurance = 0;

        foreach ($loans as $loan) {
            $schedule = $this->amortise($loan, $today, $horizon);
            $schedules[] = $schedule;

            $totalPrincipal += $loan->getPrincipalRemainingCents();
            $totalMonthly += $loan->getMonthlyPaymentCents();
            $totalInterest += $schedule['totalInterestCents'];
            $totalInsurance += $loan->insuranceMonthlyCentsDue();
        }

        // Sorted by when they free up: the next relief comes first.
        usort($schedules, static function (array $a, array $b) {
            if (null === $a['freedOn'] || null === $b['freedOn']) {
                return null === $a['freedOn'] ? 1 : -1;
            }

            return $a['freedOn'] <=> $b['freedOn'];
        });

        $lifestyle = $this->measureMonthlyLifestyle->execute($user, $today);
        $income = $this->getReferenceSalary->execute($user)['monthlyCents'] ?? 0;

        return [
            'horizonMonths' => $horizon,
            'totalPrincipalRemainingCents' => $totalPrincipal,
            'totalMonthlyPaymentCents' => $totalMonthly,
            'totalInterestOverHorizonCents' => $totalInterest,
            'loans' => $schedules,
            // Money freed month by month as loans end, over the horizon.
            'reliefByMonth' => $this->reliefByMonth($schedules, $today, $horizon),
            'savingCapacity' => [
                'monthlyNetIncomeCents' => $income,
                // The insurance leaves the account every month too (MAG-522).
                'loanPaymentsCents' => $totalMonthly + $totalInsurance,
                'estimatedLifestyleCents' => $lifestyle,
                'netCapacityCents' => $income - $totalMonthly - $totalInsurance - $lifestyle,
                'isIncomeKnown' => $income > 0,
            ],
        ];
    }

    /** @return array<string, mixed> */
    private function amortise(Loan $loan, \DateTimeImmutable $from, int $horizon): array
    {
        $rows = $this->loanSchedule->rows($loan, $from, $horizon);
        $last = [] === $rows ? null : $rows[array_key_last($rows)];
        $freedOn = null !== $last && 0 === $last['remainingCents'] ? $last['month'] : null;
        $months = \count($rows);
        $interestPaid = array_sum(array_column($rows, 'interestCents'));

        return [
            'id' => (string) $loan->getId(),
            'name' => $loan->getName(),
            'lender' => $loan->getLender(),
            'currency' => $loan->getCurrency(),
            'principalRemainingCents' => $loan->getPrincipalRemainingCents(),
            'monthlyPaymentCents' => $loan->getMonthlyPaymentCents(),
            'annualRateBasisPoints' => $loan->getAnnualRateBasisPoints(),
            'priority' => $loan->getPriority(),
            'type' => $loan->getType()->value,
            'insuranceMonthlyCents' => $loan->insuranceMonthlyCentsDue(),
            'monthsRemaining' => null === $freedOn ? null : $months,
            'freedOn' => $freedOn,
            'totalInterestCents' => $interestPaid,
            // Still running at the end of the horizon: we only know it is longer.
            'endsBeyondHorizon' => null === $freedOn,
        ];
    }

    /**
     * @param array<int, array<string, mixed>> $schedules
     *
     * @return array<int, array{month: string, freedCents: int, cumulativeFreedCents: int, loans: list<string>}>
     */
    private function reliefByMonth(array $schedules, \DateTimeImmutable $from, int $horizon): array
    {
        $freedByMonth = [];
        foreach ($schedules as $schedule) {
            if (null === $schedule['freedOn']) {
                continue;
            }
            $freedByMonth[$schedule['freedOn']][] = $schedule;
        }

        if ([] === $freedByMonth) {
            return [];
        }

        ksort($freedByMonth);

        $cumulative = 0;
        $relief = [];
        foreach ($freedByMonth as $month => $endingLoans) {
            $freed = array_sum(array_map(
                static fn (array $loan) => $loan['monthlyPaymentCents'],
                $endingLoans,
            ));
            $cumulative += $freed;

            $relief[] = [
                'month' => (string) $month,
                'freedCents' => $freed,
                'cumulativeFreedCents' => $cumulative,
                'loans' => array_map(static fn (array $loan) => $loan['name'], $endingLoans),
            ];
        }

        return $relief;
    }
}
