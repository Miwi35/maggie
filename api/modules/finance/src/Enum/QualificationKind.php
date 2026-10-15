<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/**
 * What a movement is beyond its category. The direction is never part of it:
 * it is the sign of the amount — a `repayment` is « je rembourse » on a debit
 * and « on me rembourse » on a credit.
 */
enum QualificationKind: string
{
    case None = 'none';
    case Saving = 'saving';
    case Investment = 'investment';
    case Loan = 'loan';
    case Repayment = 'repayment';
}
