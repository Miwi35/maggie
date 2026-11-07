<?php

declare(strict_types=1);

namespace Maggie\Calendar\Enum;

/**
 * When in its scheduled day a task is planned (MAG-718): a part of the day,
 * or a precise time — the only one that takes reminders.
 */
enum TaskMoment: string
{
    case Morning = 'morning';
    case Afternoon = 'afternoon';
    case Evening = 'evening';
    case Time = 'time';

    /** The part of the day an hour `HH:MM` falls in: before noon, before six, then the evening. */
    public static function partOf(string $time): self
    {
        return match (true) {
            $time < '12:00' => self::Morning,
            $time < '18:00' => self::Afternoon,
            default => self::Evening,
        };
    }
}
