<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/**
 * What an early repayment costs (MAG-522, spec rule 8).
 *
 * `legal_cap`: the French cap on a mortgage, the lesser of six months of
 * interest on the amount repaid and 3 % of the capital owed. `fixed`: the
 * amount the offer states. `exempt`: nothing. `consumer`: 1 % of the amount
 * repaid with more than twelve months left, 0.5 % otherwise.
 */
enum PrepaymentPenaltyMode: string
{
    case LegalCap = 'legal_cap';
    case Fixed = 'fixed';
    case Exempt = 'exempt';
    case Consumer = 'consumer';
}
