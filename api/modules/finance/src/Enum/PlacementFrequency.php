<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/**
 * How often a placement's deposit is paid (MAG-555).
 *
 * `monthly`, `quarterly`, `yearly`: at the end of every period, after that
 * month's interest. `once`: a single deposit on the first day, with the
 * initial payment.
 */
enum PlacementFrequency: string
{
    case Monthly = 'monthly';
    case Quarterly = 'quarterly';
    case Yearly = 'yearly';
    case Once = 'once';

    /** Months between two deposits, 0 for a single one. */
    public function periodMonths(): int
    {
        return match ($this) {
            self::Monthly => 1,
            self::Quarterly => 3,
            self::Yearly => 12,
            self::Once => 0,
        };
    }

    /** What the deposits come to per month on average: 0 for a single one. */
    public function monthlyCents(int $depositCents): int
    {
        $months = $this->periodMonths();

        return 0 === $months ? 0 : (int) round($depositCents / $months);
    }
}
