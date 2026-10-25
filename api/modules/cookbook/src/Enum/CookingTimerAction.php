<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Enum;

/** What `PATCH /api/cooking/timers/{id}` and `manage_cooking_timers` do to a timer. */
enum CookingTimerAction: string
{
    case Pause = 'pause';
    case Resume = 'resume';
    /** Moves the end by `seconds`, 60 when none is given. */
    case Add = 'add';
    case Cancel = 'cancel';
    /** « OK » on its end. */
    case Dismiss = 'dismiss';

    /** @return list<string> */
    public static function values(): array
    {
        return array_map(static fn (self $action) => $action->value, self::cases());
    }
}
