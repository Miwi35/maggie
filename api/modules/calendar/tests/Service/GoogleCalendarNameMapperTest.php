<?php

namespace Maggie\Calendar\Tests\Service;

use Google\Service\Calendar\CalendarListEntry;
use Maggie\Calendar\Service\GoogleCalendarNameMapper;
use PHPUnit\Framework\TestCase;

/**
 * The name an imported Google calendar carries (MAG-148).
 *
 * Production showed the primary calendar as "meven cadare" while Google
 * displayed "Mon agenda", so the two applications disagreed on every screen.
 */
class GoogleCalendarNameMapperTest extends TestCase
{
    private function entry(?string $summary, ?string $summaryOverride = null, bool $primary = false): CalendarListEntry
    {
        $entry = new CalendarListEntry();
        $entry->setId('cal-id@group.calendar.google.com');
        $entry->setSummary($summary);
        $entry->setSummaryOverride($summaryOverride);
        $entry->setPrimary($primary);

        return $entry;
    }

    public function testTakesTheSummary(): void
    {
        self::assertSame('Concerts', (new GoogleCalendarNameMapper())->nameFor($this->entry('Concerts')));
    }

    public function testPrefersTheOverrideTheUserGaveInGoogle(): void
    {
        $entry = $this->entry('Agenda de Camille', 'Camille');

        self::assertSame('Camille', (new GoogleCalendarNameMapper())->nameFor($entry));
    }

    public function testNamesThePrimaryCalendarDefault(): void
    {
        $entry = $this->entry('meven35@gmail.com', primary: true);

        self::assertSame('Défaut', (new GoogleCalendarNameMapper())->nameFor($entry));
    }

    public function testIgnoresABlankOverride(): void
    {
        $entry = $this->entry('Concerts', '   ');

        self::assertSame('Concerts', (new GoogleCalendarNameMapper())->nameFor($entry));
    }

    public function testFallsBackOnTheCalendarIdWhenGoogleGivesNoName(): void
    {
        $entry = $this->entry(null);

        self::assertSame('cal-id@group.calendar.google.com', (new GoogleCalendarNameMapper())->nameFor($entry));
    }
}
