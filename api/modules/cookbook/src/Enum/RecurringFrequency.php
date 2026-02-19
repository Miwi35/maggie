<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Enum;

enum RecurringFrequency: string
{
    case Weekly = 'weekly';
    case Biweekly = 'biweekly';
    case Monthly = 'monthly';
}
