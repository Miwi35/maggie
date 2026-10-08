<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/** Which day of the month an occurrence falls on. */
enum DayRule: string
{
    /** The anchor's day, or the month's last day when the month is shorter. */
    case FixedDay = 'fixed_day';
    case LastDayOfMonth = 'last_day_of_month';
}
