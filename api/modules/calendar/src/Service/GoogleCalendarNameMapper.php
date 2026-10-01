<?php

namespace Maggie\Calendar\Service;

use Google\Service\Calendar\CalendarListEntry;

/**
 * The name an imported Google calendar carries in Maggie (MAG-148).
 *
 * Google shows the name the owner gave the calendar — `summaryOverride` when
 * they renamed a calendar someone shared with them, `summary` otherwise. The
 * primary calendar is the exception: its `summary` is the account's own label
 * (an address, or the account holder's name), which told the user nothing and
 * is not what Google displays either. It is called "Défaut" instead, the
 * agenda Maggie falls back to.
 */
class GoogleCalendarNameMapper
{
    public const string PRIMARY_NAME = 'Défaut';

    public function nameFor(CalendarListEntry $entry): string
    {
        if (true === $entry->getPrimary()) {
            return self::PRIMARY_NAME;
        }

        foreach ([$entry->getSummaryOverride(), $entry->getSummary()] as $candidate) {
            $name = trim((string) $candidate);
            if ('' !== $name) {
                return $name;
            }
        }

        // A calendar with no name at all: the id is at least recognisable, and
        // the agenda's name may not be blank.
        return (string) $entry->getId();
    }
}
