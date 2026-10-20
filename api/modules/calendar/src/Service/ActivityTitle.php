<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Event;

/**
 * The title an occurrence of an activity's event is shown under (MAG-456):
 * the monthly review is « Revue d'octobre », « Revue de novembre », whatever
 * its stored summary — the month is the one the occurrence falls in.
 */
final class ActivityTitle
{
    private const MONTHS = [
        1 => 'janvier', 'février', 'mars', 'avril', 'mai', 'juin',
        'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre',
    ];

    /** « Revue d'octobre »: « de » elided before a vowel (avril, août, octobre). */
    public static function review(\DateTimeInterface $day): string
    {
        $month = self::MONTHS[(int) $day->format('n')];

        return \in_array($month[0], ['a', 'o'], true) ? "Revue d'{$month}" : "Revue de {$month}";
    }

    /** The title of this occurrence, when its activity names one; null keeps its summary. */
    public static function ofOccurrence(Event $occurrence): ?string
    {
        if (Event::ACTIVITY_FINANCE_REVIEW !== $occurrence->getActivity()) {
            return null;
        }

        $start = null !== $occurrence->getStartAt()
            ? $occurrence->getStartAt()->setTimezone(new \DateTimeZone(self::zoneOf($occurrence)))
            : $occurrence->getStartDate();

        return null === $start ? null : self::review($start);
    }

    private static function zoneOf(Event $event): string
    {
        return \in_array($event->getTimeZone(), \DateTimeZone::listIdentifiers(), true) ? $event->getTimeZone() : Event::FALLBACK_TIME_ZONE;
    }
}
