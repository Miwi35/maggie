<?php

namespace Maggie\Calendar\Enum;

/** Why a task is alerted on a morning (MAG-407): {@see \Maggie\Calendar\Service\TaskAlertSchedule}. */
enum TaskAlertReason: string
{
    /** Due in n days, n one of the schedule's `daysBefore`. */
    case DaysBefore = 'days_before';
    /** Due this very day. */
    case DueDay = 'due_day';
    /** Late by n days, n a multiple of the schedule's `overdueEveryDays`. */
    case Overdue = 'overdue';
}
