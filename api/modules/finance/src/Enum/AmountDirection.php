<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

enum AmountDirection: string
{
    case Any = 'any';
    case Debit = 'debit';
    case Credit = 'credit';
}
