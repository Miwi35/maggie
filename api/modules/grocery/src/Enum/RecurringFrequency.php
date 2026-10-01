<?php

declare(strict_types=1);

namespace Maggie\Grocery\Enum;

enum RecurringFrequency: string
{
    case Weekly = 'weekly';
    case Biweekly = 'biweekly';
    case Monthly = 'monthly';

    /** How long the last purchase lasts before the item is wanted again. */
    public function interval(): \DateInterval
    {
        return match ($this) {
            self::Weekly => new \DateInterval('P7D'),
            self::Biweekly => new \DateInterval('P14D'),
            self::Monthly => new \DateInterval('P1M'),
        };
    }
}
