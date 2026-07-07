<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

enum TransactionStatus: string
{
    case Spent = 'spent';
    case Committed = 'committed';
    case Planned = 'planned';
    case ToArbitrate = 'to_arbitrate';
}
