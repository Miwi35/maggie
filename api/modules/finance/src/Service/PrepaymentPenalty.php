<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Enum\PrepaymentPenaltyMode;

/**
 * The early repayment penalty (IRA) an amount costs, by the loan's mode
 * (MAG-522, spec rule 8). Rounded down to the cent.
 */
final class PrepaymentPenalty
{
    public function penaltyCents(Loan $loan, int $amountCents, int $monthsRemaining): int
    {
        return match ($loan->getPrepaymentPenaltyMode()) {
            // Six months of interest at the nominal rate on the amount repaid,
            // at most 3 % of the capital still owed.
            PrepaymentPenaltyMode::LegalCap => min(
                intdiv($amountCents * $loan->currentRateBasisPoints() * 6, 10000 * 12),
                intdiv($loan->getPrincipalRemainingCents() * 3, 100),
            ),
            PrepaymentPenaltyMode::Fixed => (int) $loan->getPrepaymentPenaltyFixedCents(),
            PrepaymentPenaltyMode::Exempt => 0,
            PrepaymentPenaltyMode::Consumer => $monthsRemaining > 12
                ? intdiv($amountCents, 100)
                : intdiv($amountCents, 200),
        };
    }
}
