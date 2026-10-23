<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Enum\LoanType;
use Maggie\Finance\Enum\PartialRepaymentEffect;
use Maggie\Finance\Service\LoanSchedule;
use Maggie\Finance\Service\PrepaymentPenalty;

/**
 * What repaying a loan early would cost and save (MAG-522): the penalty, the
 * interest the amount would still have cost, and the difference. Facts only —
 * whether to do it is the user's call.
 */
class SimulateEarlyRepayment
{
    public function __construct(
        private readonly LoanSchedule $loanSchedule,
        private readonly PrepaymentPenalty $prepaymentPenalty,
    ) {
    }

    /**
     * @param int|null $amountCents null repays the whole capital
     *
     * @return array{amountCents: int, penaltyMode: string, penaltyCents: int, remainingInterestCents: int, realGainCents: int}
     *
     * @throws \DomainException when the loan or the amount does not allow it
     */
    public function execute(Loan $loan, ?int $amountCents = null, ?\DateTimeImmutable $from = null): array
    {
        if (!$loan->isEarlyRepaymentAllowed()) {
            throw new \DomainException('This loan does not allow an early repayment (earlyRepaymentAllowed is false).');
        }

        $owed = $loan->getPrincipalRemainingCents();
        if (0 === $owed) {
            throw new \DomainException('Nothing is left to repay on this loan.');
        }

        $amount = $amountCents ?? $owed;
        if ($amount <= 0 || $amount > $owed) {
            throw new \DomainException(\sprintf('amountCents must be between 1 and the capital owed, %d.', $owed));
        }

        $minimum = $loan->getMinPartialRepaymentCents();
        if ($amount < $owed && null !== $minimum && $amount < $minimum) {
            throw new \DomainException(\sprintf('The lender takes no partial repayment under %d cents.', $minimum));
        }

        $from ??= new \DateTimeImmutable('first day of this month');
        $schedule = $this->loanSchedule->rows($loan, $from);
        $interestNow = self::interestOf($schedule);
        // The months before a loan is drawn are not payments left.
        $monthsLeft = \count($schedule) - max(0, -LoanSchedule::paymentsMadeBy($loan, $from->modify('first day of this month')));

        $interestAfter = 0;
        if ($amount < $owed) {
            $interestAfter = self::interestOf($this->loanSchedule->rows(
                $loan,
                $from,
                principalCents: $owed - $amount,
                monthlyPaymentCents: $this->paymentAfter($loan, $owed - $amount, $monthsLeft),
            ));
        }

        $remainingInterest = $interestNow - $interestAfter;
        $penalty = $this->prepaymentPenalty->penaltyCents($loan, $amount, $monthsLeft);

        return [
            'amountCents' => $amount,
            'penaltyMode' => $loan->getPrepaymentPenaltyMode()->value,
            'penaltyCents' => $penalty,
            'remainingInterestCents' => $remainingInterest,
            'realGainCents' => self::realGainCents($remainingInterest, $penalty),
        ];
    }

    /** What the repayment saves once the penalty is paid: negative when it costs more than it saves. */
    public static function realGainCents(int $remainingInterestCents, int $penaltyCents): int
    {
        return $remainingInterestCents - $penaltyCents;
    }

    /**
     * The payment after a partial repayment: the same one (the loan ends
     * sooner) unless the offer lowers it over the months left — which only
     * means something for an amortized loan.
     */
    public function paymentAfter(Loan $loan, int $principalAfter, int $monthsLeft): ?int
    {
        if (PartialRepaymentEffect::LowerPayment !== $loan->getPartialRepaymentEffect()
            || !\in_array($loan->getType(), [LoanType::AmortizingFixed, LoanType::AmortizingVariable], true)) {
            return null;
        }

        return LoanSchedule::annuity($principalAfter, $loan->currentRateBasisPoints() / (10000 * 12), $monthsLeft);
    }

    /** @param list<array{interestCents: int}> $rows */
    private static function interestOf(array $rows): int
    {
        return array_sum(array_column($rows, 'interestCents'));
    }
}
