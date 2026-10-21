<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/** After a partial early repayment (MAG-522): the loan ends sooner, or the payment drops. */
enum PartialRepaymentEffect: string
{
    case Shorten = 'shorten';
    case LowerPayment = 'lower_payment';
}
