<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Repository\LoanRepository;
use Maggie\Finance\Repository\SafetyCushionRepository;

/**
 * When each fixed charge falls away, and what is actually left to save.
 *
 * The schedule is recomputed, never stored: a stored one would have to be
 * reconciled with reality at every payment.
 */
class GetDebtTimeline
{
    private const DEFAULT_HORIZON_MONTHS = 60;

    public function __construct(
        private readonly LoanRepository $loanRepository,
        private readonly MeasureMonthlyLifestyle $measureMonthlyLifestyle,
        private readonly SafetyCushionRepository $cushionRepository,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(User $user, ?int $horizonMonths = null): array
    {
        $horizon = $horizonMonths ?? self::DEFAULT_HORIZON_MONTHS;
        $loans = $this->loanRepository->findByUser($user);
        $today = new \DateTimeImmutable('first day of this month');

        $schedules = [];
        $totalPrincipal = 0;
        $totalMonthly = 0;
        $totalInterest = 0;

        foreach ($loans as $loan) {
            $schedule = $this->amortise($loan, $today, $horizon);
            $schedules[] = $schedule;

            $totalPrincipal += $loan->getPrincipalRemainingCents();
            $totalMonthly += $loan->getMonthlyPaymentCents();
            $totalInterest += $schedule['totalInterestCents'];
        }

        // Sorted by when they free up: the next relief comes first.
        usort($schedules, static function (array $a, array $b) {
            if (null === $a['freedOn'] || null === $b['freedOn']) {
                return null === $a['freedOn'] ? 1 : -1;
            }

            return $a['freedOn'] <=> $b['freedOn'];
        });

        $lifestyle = $this->measureMonthlyLifestyle->execute($user, $today);
        $income = $this->cushionRepository->findOneByUser($user)?->getMonthlyNetIncomeCents() ?? 0;

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
                'loanPaymentsCents' => $totalMonthly,
                'estimatedLifestyleCents' => $lifestyle,
                'netCapacityCents' => $income - $totalMonthly - $lifestyle,
                'isIncomeKnown' => $income > 0,
            ],
        ];
    }

    /**
     * Month by month, the way a bank does it: interest on what is still owed,
     * whatever is left of the payment eats into the capital.
     *
     * @return array<string, mixed>
     */
    private function amortise(Loan $loan, \DateTimeImmutable $from, int $horizon): array
    {
        $principal = $loan->getPrincipalRemainingCents();
        $payment = $loan->getMonthlyPaymentCents();

        $months = 0;
        $interestPaid = 0;
        $freedOn = null;

        while ($principal > 0 && $months < $horizon) {
            $interest = $loan->monthlyInterestOn($principal);
            $towardsCapital = $payment - $interest;

            if ($towardsCapital <= 0) {
                // Guarded at the door, but never trust a row already in the table.
                break;
            }

            $principal = max(0, $principal - $towardsCapital);
            $interestPaid += $interest;
            ++$months;

            if (0 === $principal) {
                $freedOn = $from->modify(sprintf('+%d months', $months))->format('Y-m');
            }
        }

        return [
            'id' => (string) $loan->getId(),
            'name' => $loan->getName(),
            'lender' => $loan->getLender(),
            'currency' => $loan->getCurrency(),
            'principalRemainingCents' => $loan->getPrincipalRemainingCents(),
            'monthlyPaymentCents' => $loan->getMonthlyPaymentCents(),
            'annualRateBasisPoints' => $loan->getAnnualRateBasisPoints(),
            'priority' => $loan->getPriority(),
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
