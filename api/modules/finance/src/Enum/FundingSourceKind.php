<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/**
 * Where part of a project's money comes from (MAG-524). What no source covers
 * is saved on the salary.
 *
 * `savings`: savings outside the cushion, on a savings or checking account.
 * `capital`: capital freed from an investment account. `loan`: a projected loan.
 */
enum FundingSourceKind: string
{
    case Savings = 'savings';
    case Capital = 'capital';
    case Loan = 'loan';
}
