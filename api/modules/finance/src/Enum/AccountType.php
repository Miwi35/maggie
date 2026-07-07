<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

enum AccountType: string
{
    case Checking = 'checking';
    case Savings = 'savings';
    case Investment = 'investment';
    case Cash = 'cash';
}
