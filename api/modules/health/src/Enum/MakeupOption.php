<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/**
 * How a missed planned session is made up (MAG-771): the same session softer,
 * a shorter one tonight, or the same one tomorrow. Also the buttons of the
 * `workout_makeup` notification, in this order.
 */
enum MakeupOption: string
{
    case Softer = 'softer';
    case Shorter = 'shorter';
    case Tomorrow = 'tomorrow';

    /** @return list<string> */
    public static function values(): array
    {
        return array_map(static fn (self $option): string => $option->value, self::cases());
    }
}
