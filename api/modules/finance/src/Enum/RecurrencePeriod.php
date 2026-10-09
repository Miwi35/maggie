<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

enum RecurrencePeriod: string
{
    case Weekly = 'weekly';
    case Monthly = 'monthly';
    case Quarterly = 'quarterly';
    case Yearly = 'yearly';

    /** Months between two occurrences; null for a period counted in days. */
    public function months(): ?int
    {
        return match ($this) {
            self::Weekly => null,
            self::Monthly => 1,
            self::Quarterly => 3,
            self::Yearly => 12,
        };
    }

    /** Occurrences in a year — 52 weeks, the way a budget counts them. */
    public function perYear(): int
    {
        return match ($this) {
            self::Weekly => 52,
            self::Monthly => 12,
            self::Quarterly => 4,
            self::Yearly => 1,
        };
    }
}
