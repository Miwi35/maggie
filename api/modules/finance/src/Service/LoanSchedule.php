<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Enum\LoanType;

/**
 * A loan's schedule, month by month, the way its type repays it (MAG-522).
 *
 * Recomputed from the loan every time, never stored: a stored schedule would
 * have to be reconciled with reality at every payment. The first row is next
 * month; `firstPaymentOn` places it within the loan's life (the deferral, the
 * steps, the last payment of an in fine), and without it the next payment is
 * the first. One row a month whatever the periodicity: `monthlyPaymentCents`
 * is the monthly equivalent.
 *
 * Interest is rounded down to the cent, as the debt timeline always did, so an
 * `amortizing_fixed` loan keeps the timeline it had — except interest added to
 * the capital during a total deferral, rounded to the nearest cent as a
 * capitalisation table does.
 */
final class LoanSchedule
{
    /** Fifty years: a loan still running past them is a loan that never ends. */
    public const MAX_MONTHS = 600;

    /**
     * `paymentCents` is what leaves the account: interest paid, capital and
     * insurance. During a total deferral `interestCents` is added to the
     * capital and nothing but the insurance is paid. The rows stop when the
     * capital is repaid, at the horizon, or at a payment that no longer covers
     * the interest — such a loan is never repaid: refused at the door for the
     * types that must amortise now, shown as ending beyond the horizon for a
     * step or a post-deferral payment too low.
     *
     * @return list<array{month: string, paymentCents: int, interestCents: int, capitalCents: int, insuranceCents: int, remainingCents: int}>
     */
    public function rows(
        Loan $loan,
        \DateTimeImmutable $from,
        int $horizonMonths = self::MAX_MONTHS,
        ?int $principalCents = null,
        ?int $monthlyPaymentCents = null,
    ): array {
        $principal = $principalCents ?? $loan->getPrincipalRemainingCents();
        $payment = $monthlyPaymentCents ?? $loan->getMonthlyPaymentCents();
        $insurance = $loan->insuranceMonthlyCentsDue();
        $start = $from->modify('first day of this month')->setTime(0, 0);
        $paidBefore = self::paymentsMadeBy($loan, $start);

        $rows = [];
        for ($month = 1; $month <= $horizonMonths && $principal > 0; ++$month) {
            $label = $start->modify(\sprintf('+%d months', $month))->format('Y-m');
            $index = $paidBefore + $month;

            if ($index < 1) {
                // Not drawn yet: nothing is owed this month.
                $rows[] = self::row($label, 0, 0, 0, $principal);
                continue;
            }

            if (LoanType::DeferredTotal === $loan->getType() && $index <= (int) $loan->getDeferralMonths()) {
                $interest = (int) round($principal * $loan->currentRateBasisPoints() / (10000 * 12));
                $principal += $interest;
                // Added to the capital, not paid: only the insurance leaves the account.
                $rows[] = self::row($label, $interest, 0, $insurance, $principal, interestPaid: false);
                continue;
            }

            $interest = $loan->monthlyInterestOn($principal);
            $capital = $this->capitalDue($loan, $index, $principal, $payment, $interest);
            if (null === $capital) {
                break;
            }

            $principal -= $capital;
            $rows[] = self::row($label, $interest, $capital, $insurance, $principal);
        }

        return $rows;
    }

    /**
     * The regular payment an offer implies, insurance aside: the annuity for
     * an amortized loan (after the deferral for a deferred one, on the
     * capital the deferral grew), the interest for an in fine or a bridge.
     */
    public function monthlyPaymentFor(LoanConditions $conditions): int
    {
        $rate = $conditions->annualRateBasisPoints / (10000 * 12);
        $amount = $conditions->amountCents;

        if ($conditions->type->isInterestOnly()) {
            return intdiv($amount * $conditions->annualRateBasisPoints, 10000 * 12);
        }

        $months = $conditions->durationMonths;
        if ($conditions->type->isDeferred()) {
            $months -= $conditions->deferralMonths;
            if (LoanType::DeferredTotal === $conditions->type) {
                for ($i = 0; $i < $conditions->deferralMonths; ++$i) {
                    $amount += (int) round($amount * $rate);
                }
            }
        }

        return self::annuity($amount, $rate, max(1, $months));
    }

    /** The constant payment that repays `amountCents` in `months` at a monthly `rate`. */
    public static function annuity(int $amountCents, float $rate, int $months): int
    {
        if ($months < 1) {
            return $amountCents;
        }

        if (0.0 === $rate) {
            return (int) ceil($amountCents / $months);
        }

        return (int) round($amountCents * $rate / (1 - (1 + $rate) ** -$months));
    }

    /**
     * How many payments the loan has had by `$month`: the next payment is that
     * plus one. 0 without `firstPaymentOn`; negative while it lies ahead.
     */
    public static function paymentsMadeBy(Loan $loan, \DateTimeImmutable $month): int
    {
        $first = $loan->getFirstPaymentOn();
        if (null === $first) {
            return 0;
        }

        $elapsed = ((int) $month->format('Y') - (int) $first->format('Y')) * 12
            + (int) $month->format('n') - (int) $first->format('n');

        return $elapsed + 1;
    }

    /** The capital a payment repays, or null when it does not cover the interest. */
    private function capitalDue(Loan $loan, int $index, int $principal, int $payment, int $interest): ?int
    {
        $type = $loan->getType();

        if ($type->isInterestOnly()) {
            return $index >= (int) $loan->getDurationMonths() ? $principal : 0;
        }

        if (LoanType::DeferredPartial === $type && $index <= (int) $loan->getDeferralMonths()) {
            return 0;
        }

        if (LoanType::Stepped === $type) {
            $payment = self::stepPayment($loan, $index) ?? $payment;
        }

        $towardsCapital = $payment - $interest;
        if ($towardsCapital <= 0) {
            return null;
        }

        return min($towardsCapital, $principal);
    }

    /** The payment of the step the `$index`-th payment falls in. */
    private static function stepPayment(Loan $loan, int $index): ?int
    {
        $payment = null;
        $from = 0;
        foreach ($loan->getSteps() ?? [] as $step) {
            if ($step['fromMonth'] <= $index && $step['fromMonth'] >= $from) {
                $payment = $step['monthlyPaymentCents'];
                $from = $step['fromMonth'];
            }
        }

        return $payment;
    }

    /** @return array{month: string, paymentCents: int, interestCents: int, capitalCents: int, insuranceCents: int, remainingCents: int} */
    private static function row(string $month, int $interest, int $capital, int $insurance, int $remaining, bool $interestPaid = true): array
    {
        return [
            'month' => $month,
            'paymentCents' => ($interestPaid ? $interest : 0) + $capital + $insurance,
            'interestCents' => $interest,
            'capitalCents' => $capital,
            'insuranceCents' => $insurance,
            'remainingCents' => $remaining,
        ];
    }
}
