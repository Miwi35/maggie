<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Service\LoanSchedule;
use Maggie\Finance\Service\ProjectFundingRules;

/**
 * The three base options of a project, priced on its amount alone — the other
 * projects wait in the planning (MAG-526) — (MAG-524, rules 2, 11 and 13):
 *
 * - cash: saved on the net saving capacity, nothing borrowed;
 * - loan: the whole amount borrowed, available now;
 * - partial: a down payment saved first, the rest borrowed.
 *
 * Facts only, never a recommendation. The net cost is interest + insurance +
 * fees minus what the capital kept would earn, placed at `yieldBasisPoints`
 * compounded monthly over the loan's duration: it can be negative, that is a
 * fact too. `nImpactMonths` stays null until part 9 computes it.
 *
 * The loan's conditions are the project's loan source's; the rate and the
 * duration given override them. Without a source, they must be given: nothing
 * is deduced.
 */
class GetProjectOptions
{
    public function __construct(
        private readonly GetDebtTimeline $getDebtTimeline,
        private readonly LoanSchedule $loanSchedule,
        private readonly ProjectFundingRules $fundingRules,
    ) {
    }

    /**
     * @return array<string, mixed>
     *
     * @throws \DomainException listing what is missing, or what does not hold
     */
    public function execute(Project $project, User $user, ?int $loanRateBasisPoints, ?int $loanMonths, ?int $downPaymentCents, ?int $yieldBasisPoints): array
    {
        $amount = $project->getAmountCents();
        $source = $project->loanSource();
        $sourceLoan = $source?->getLoan();
        $downPaymentCents ??= null === $source ? null : $amount - $source->getAmountCents();

        $missing = [];
        if (null === $sourceLoan && null === $loanRateBasisPoints) {
            $missing[] = 'loanRateBasisPoints';
        }
        if (null === $sourceLoan && null === $loanMonths) {
            $missing[] = 'loanMonths';
        }
        if (null === $downPaymentCents) {
            $missing[] = 'downPaymentCents';
        }
        if (null === $yieldBasisPoints) {
            $missing[] = 'yieldBasisPoints';
        }
        if ([] !== $missing) {
            throw new \DomainException('Missing to price the options: '.implode(', ', $missing).'. Ask for them: nothing is deduced.');
        }
        \assert(null !== $downPaymentCents && null !== $yieldBasisPoints);

        if ($downPaymentCents < 0 || $downPaymentCents >= $amount) {
            throw new \DomainException(\sprintf('downPaymentCents must be from 0 to less than the project\'s %d cents.', $amount));
        }
        if ($yieldBasisPoints < 0 || $yieldBasisPoints > 5000) {
            throw new \DomainException('yieldBasisPoints must be from 0 to 5000 (300 = 3 %).');
        }

        $terms = $this->terms($project, $user, $sourceLoan, $loanRateBasisPoints, $loanMonths);
        $capacity = $this->getDebtTimeline->execute($user, 1)['savingCapacity']['netCapacityCents'];

        return [
            'projectId' => (string) $project->getId(),
            'amountCents' => $amount,
            'currency' => $project->getCurrency(),
            'netCapacityCents' => $capacity,
            'loanRateBasisPoints' => $terms->getAnnualRateBasisPoints(),
            'loanMonths' => $terms->getDurationMonths(),
            'downPaymentCents' => $downPaymentCents,
            'yieldBasisPoints' => $yieldBasisPoints,
            'cash' => [
                'principalCents' => 0,
                'downPaymentCents' => $amount,
                'interestCents' => 0,
                'insuranceCents' => 0,
                'feesCents' => 0,
                'yieldCents' => 0,
                'totalCostCents' => $amount,
                'netCostCents' => 0,
                'monthlyPaymentCents' => 0,
                'durationMonths' => 0,
                'availableInMonths' => self::monthsToSave($amount, $capacity),
                'capitalKeptCents' => 0,
                'nImpactMonths' => null,
            ],
            'loan' => $this->borrowing($terms, 0, $amount, $capacity, $yieldBasisPoints),
            'partial' => $this->borrowing($terms, $downPaymentCents, $amount - $downPaymentCents, $capacity, $yieldBasisPoints),
        ];
    }

    /** The offer the options borrow on, checked as any loan is: a copy, never saved. */
    private function terms(Project $project, User $user, ?Loan $sourceLoan, ?int $rate, ?int $months): Loan
    {
        if (null === $sourceLoan) {
            $offer = $this->fundingRules->projectedLoan($project, $user, $project->getAmountCents(), [
                'annualRateBasisPoints' => $rate,
                'durationMonths' => $months,
            ]);
        } else {
            // Every other condition — type, insurance, fees, deferral — is the source's.
            $offer = new Loan();
            ProjectFundingRules::copy($sourceLoan, $offer);
            $offer->setUser($user);
            if (null !== $rate) {
                if ($rate < 0 || $rate > 5000) {
                    throw new \DomainException('loanRateBasisPoints must be from 0 to 5000 (400 = 4 %).');
                }
                $offer->setAnnualRateBasisPoints($rate);
            }
            if (null !== $months) {
                $offer->setDurationMonths($months);
            }
        }

        // Priced from the first payment on, whenever the loan would start.
        $offer->setFirstPaymentOn(null);

        return $offer;
    }

    /** @return array<string, int|null> */
    private function borrowing(Loan $terms, int $downPaymentCents, int $principalCents, int $capacity, int $yieldBasisPoints): array
    {
        $loan = new Loan();
        ProjectFundingRules::copy($terms, $loan);
        $loan->setUser($terms->getUser());
        $this->fundingRules->borrow($loan, $principalCents);

        $rows = $this->loanSchedule->rows($loan, new \DateTimeImmutable('first day of this month'));
        $paid = array_sum(array_column($rows, 'paymentCents'));
        $insurance = array_sum(array_column($rows, 'insuranceCents'));
        $interest = $paid - $insurance - $principalCents;
        $fees = (int) $loan->getFeesCents();
        $months = (int) $loan->getDurationMonths();
        $yield = self::yieldOn($principalCents, $yieldBasisPoints, $months);

        return [
            'principalCents' => $principalCents,
            'downPaymentCents' => $downPaymentCents,
            'interestCents' => $interest,
            'insuranceCents' => $insurance,
            'feesCents' => $fees,
            'yieldCents' => $yield,
            'totalCostCents' => $downPaymentCents + $principalCents + $interest + $insurance + $fees,
            'netCostCents' => $interest + $insurance + $fees - $yield,
            'monthlyPaymentCents' => $loan->getMonthlyPaymentCents(),
            'durationMonths' => $months,
            'availableInMonths' => self::monthsToSave($downPaymentCents, $capacity),
            'capitalKeptCents' => $principalCents,
            'nImpactMonths' => null,
        ];
    }

    /** What `capitalCents` placed at `yieldBasisPoints` a year earns, compounded monthly, over `months`. */
    public static function yieldOn(int $capitalCents, int $yieldBasisPoints, int $months): int
    {
        return (int) round($capitalCents * ((1 + $yieldBasisPoints / (10000 * 12)) ** $months - 1));
    }

    /** Months of net saving capacity to put `cents` aside; null when nothing is left to save. */
    public static function monthsToSave(int $cents, int $capacity): ?int
    {
        if (0 === $cents) {
            return 0;
        }

        return $capacity > 0 ? (int) ceil($cents / $capacity) : null;
    }
}
