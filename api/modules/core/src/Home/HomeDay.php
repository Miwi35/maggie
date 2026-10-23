<?php

declare(strict_types=1);

namespace Maggie\Core\Home;

use Maggie\Core\Time\DayBound;
use Symfony\Component\Clock\ClockInterface;

/** The day the home page is showing: the selector « Aujourd'hui / Demain » (MAG-560). */
enum HomeDay: string
{
    case Today = 'today';
    case Tomorrow = 'tomorrow';

    /**
     * That day in the owner's zone, as a calendar day at midnight UTC (the
     * shape DayBound and the date columns use). Reads the injected clock, so
     * a MockClock moves it.
     */
    public function date(ClockInterface $clock): \DateTimeImmutable
    {
        $today = DayBound::firstDay($clock->now());

        return self::Today === $this ? $today : $today->modify('+1 day');
    }
}
