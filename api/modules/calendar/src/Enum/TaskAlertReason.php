<?php

namespace Maggie\Calendar\Enum;

/**
 * Why a task is brought up on a morning (MAG-407): {@see \Maggie\Calendar\Service\TaskAlertSchedule}.
 * The first two are « À programmer », the last « Pas faite » (MAG-718).
 */
enum TaskAlertReason: string
{
    /** Due in n days, n one of the schedule's `daysBefore`. */
    case DaysBefore = 'days_before';
    /** Due this very day. */
    case DueDay = 'due_day';
    /** Late by n days, n a multiple of the schedule's `overdueEveryDays`. */
    case Overdue = 'overdue';

    /** What the morning digest calls it: `to_schedule` or `not_done`. */
    public function digestReason(): string
    {
        return self::Overdue === $this ? 'not_done' : 'to_schedule';
    }
}
