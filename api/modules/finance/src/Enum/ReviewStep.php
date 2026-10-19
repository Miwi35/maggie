<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/** The steps of the monthly review activity, in the order it walks them (MAG-456). */
enum ReviewStep: string
{
    case Review = 'review';
    case Balance = 'balance';
    case Envelopes = 'envelopes';
    case Upcoming = 'upcoming';
    case Projects = 'projects';
    case Done = 'done';

    /** @return list<string> */
    public static function values(): array
    {
        return array_map(static fn (self $step): string => $step->value, self::cases());
    }
}
