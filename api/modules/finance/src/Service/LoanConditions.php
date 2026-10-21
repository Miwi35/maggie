<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Enum\LoanType;

/**
 * The terms an offer gives before any loan exists (MAG-522): what
 * `LoanSchedule::monthlyPaymentFor()` turns into a monthly payment, for a
 * projected loan or an option of a project's funding.
 */
final readonly class LoanConditions
{
    public function __construct(
        public LoanType $type,
        public int $amountCents,
        public int $annualRateBasisPoints,
        public int $durationMonths,
        public int $deferralMonths = 0,
    ) {
    }
}
