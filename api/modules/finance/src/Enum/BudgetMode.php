<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

enum BudgetMode: string
{
    case Monthly = 'monthly';
    case Annual = 'annual';
}
