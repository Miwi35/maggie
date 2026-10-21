<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/**
 * How often the lender takes a payment (MAG-522). Recorded as the offer says;
 * the schedule stays monthly, `monthlyPaymentCents` being the monthly
 * equivalent.
 */
enum LoanPeriodicity: string
{
    case Monthly = 'monthly';
    case Quarterly = 'quarterly';
    case Semiannual = 'semiannual';
    case Annual = 'annual';
}
