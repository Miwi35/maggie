<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Service;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Service\EventReminders;
use PHPUnit\Framework\TestCase;

/**
 * The one place a delay becomes a reminder, and back.
 *
 * Everything that is not the Google import goes through here — the MCP tools
 * today, and whatever speaks in minutes tomorrow. The shape it builds is the
 * only one `CheckRemindersCommand` reads, so a mistake here is a reminder the
 * owner never receives and nothing reports (MAG-121).
 */
final class EventRemindersTest extends TestCase
{
    public function testADelayBecomesGooglesShape(): void
    {
        self::assertSame(
            ['useDefault' => false, 'overrides' => [['method' => 'popup', 'minutes' => 60]]],
            EventReminders::fromMinutes([60]),
        );
    }

    /** Sorted and deduplicated: "une heure avant, et aussi une heure avant" is one reminder. */
    public function testDelaysAreSortedAndSaidOnce(): void
    {
        self::assertSame(
            [10, 60, 1440],
            EventReminders::toMinutes(EventReminders::fromMinutes([1440, 10, 60, 10])),
        );
    }

    /** No delay at all is no reminders field — which is what clears it. */
    public function testNoDelayIsNoReminders(): void
    {
        self::assertNull(EventReminders::fromMinutes([]));
    }

    public function testADelayOutsideWhatGoogleAcceptsIsRefused(): void
    {
        $this->expectException(\DomainException::class);

        EventReminders::fromMinutes([Event::MAX_REMINDER_MINUTES + 1]);
    }

    public function testZeroIsRefusedBecauseTheCronSkipsIt(): void
    {
        $this->expectException(\DomainException::class);

        EventReminders::fromMinutes([0]);
    }

    public function testMoreRemindersThanGoogleAcceptsIsRefused(): void
    {
        $this->expectException(\DomainException::class);

        EventReminders::fromMinutes(range(1, Event::MAX_REMINDERS + 1));
    }

    /**
     * What a Google-imported event may hold, read back without blowing up.
     *
     * `useDefault` alone, a bare list, a delay of zero: all shapes the cron
     * ignores, so reading them says "no reminder" rather than throwing at the
     * owner over something Google wrote.
     */
    public function testReadingBackIgnoresWhatTheCronIgnores(): void
    {
        self::assertSame([], EventReminders::toMinutes(null));
        self::assertSame([], EventReminders::toMinutes(['useDefault' => true]));
        self::assertSame([], EventReminders::toMinutes([['method' => 'popup', 'minutes' => 30]]));
        self::assertSame([], EventReminders::toMinutes(['overrides' => [['method' => 'popup', 'minutes' => 0]]]));
    }
}
