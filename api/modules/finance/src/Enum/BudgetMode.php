<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/**
 * Monthly envelopes make the month's budget; annual ones budget a calendar
 * year apart from it; a one-off one budgets an event (Hellfest 2026, a trip)
 * with neither period nor category, spends being attributed to it by hand.
 */
enum BudgetMode: string
{
    case Monthly = 'monthly';
    case Annual = 'annual';
    case OneOff = 'one_off';
}
