<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/**
 * The steps of the review activity, in the order it walks them (MAG-456). The
 * last two are walked only by the reviews that close a quarter, a semester or a
 * year (MAG-462).
 */
enum ReviewStep: string
{
    case Review = 'review';
    case Balance = 'balance';
    case Envelopes = 'envelopes';
    case Upcoming = 'upcoming';
    case Projects = 'projects';
    case PeriodBalance = 'period_balance';
    case PeriodPlan = 'period_plan';
    case Done = 'done';

    /** @return list<string> */
    public static function values(): array
    {
        return array_map(static fn (self $step): string => $step->value, self::cases());
    }
}
