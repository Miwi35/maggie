<?php

declare(strict_types=1);

namespace Maggie\Calendar\Enum;

/**
 * The moments of a day a reminder or a routine is anchored to (MAG-510),
 * rather than a fixed hour: each user sets the hour of each one
 * ({@see \Maggie\Calendar\Entity\TaskSettings::getDayMoments()}).
 */
enum DayMoment: string
{
    case Wake = 'wake';
    case AfterBreakfast = 'afterBreakfast';
    case AfterLunch = 'afterLunch';
    case AfterDinner = 'afterDinner';

    /** The hours before any setting, `HH:MM` in the user's timezone. */
    public const DEFAULTS = [
        'wake' => '07:30',
        'afterBreakfast' => '08:30',
        'afterLunch' => '13:30',
        'afterDinner' => '20:00',
    ];
}
