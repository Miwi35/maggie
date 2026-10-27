<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

/**
 * The day a trip is listed on (MAG-654): the Paris day it leaves on — the
 * day of `departAt`, read in Europe/Paris like every date the user gives.
 */
final class TripDay
{
    public const string TIME_ZONE = 'Europe/Paris';

    /**
     * @return array{\DateTimeImmutable, \DateTimeImmutable} from midnight included to the next midnight excluded
     *
     * @throws \InvalidArgumentException when $date is not a YYYY-MM-DD day
     */
    public static function bounds(string $date): array
    {
        $day = \DateTimeImmutable::createFromFormat('!Y-m-d', $date, new \DateTimeZone(self::TIME_ZONE));
        if (false === $day || $day->format('Y-m-d') !== $date) {
            throw new \InvalidArgumentException("« {$date} » is not a day: send YYYY-MM-DD.");
        }

        return [$day, $day->modify('+1 day')];
    }
}
