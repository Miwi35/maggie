<?php

namespace Maggie\Calendar\Tests\Service;

use Google\Service\Calendar\Event as GoogleEvent;
use Google\Service\Calendar\EventDateTime;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Service\GoogleEventMapper;
use PHPUnit\Framework\TestCase;

/** MAG-246: a tentative event is tentative on both sides of the Google sync. */
class GoogleEventMapperTest extends TestCase
{
    private function maggieEvent(EventStatus $status): Event
    {
        return (new Event())
            ->setSummary('Déjeuner')
            ->setStartAt(new \DateTimeImmutable('2026-10-15 12:00'))
            ->setEndAt(new \DateTimeImmutable('2026-10-15 13:00'))
            ->setStatus($status);
    }

    private function googleEvent(?string $status): GoogleEvent
    {
        $start = new EventDateTime();
        $start->setDateTime('2026-10-15T12:00:00+02:00');
        $end = new EventDateTime();
        $end->setDateTime('2026-10-15T13:00:00+02:00');

        $googleEvent = new GoogleEvent();
        $googleEvent->setSummary('Déjeuner');
        $googleEvent->setStart($start);
        $googleEvent->setEnd($end);
        $googleEvent->setStatus($status);

        return $googleEvent;
    }

    public function testATentativeEventIsSentToGoogleAsTentative(): void
    {
        $googleEvent = (new GoogleEventMapper())->toGoogle($this->maggieEvent(EventStatus::Tentative));

        self::assertSame('tentative', $googleEvent->getStatus());
    }

    public function testAConfirmedEventIsSentToGoogleAsConfirmed(): void
    {
        $googleEvent = (new GoogleEventMapper())->toGoogle($this->maggieEvent(EventStatus::Confirmed));

        self::assertSame('confirmed', $googleEvent->getStatus());
    }

    public function testTheStatusRidesAlongInAPatchOnlyWhenItChanged(): void
    {
        $mapper = new GoogleEventMapper();
        $event = $this->maggieEvent(EventStatus::Tentative);

        self::assertSame('tentative', $mapper->toGooglePatch($event, ['status'])->getStatus());
        self::assertNull($mapper->toGooglePatch($event, ['summary'])->getStatus());
    }

    public function testATentativeEventFromGoogleIsTentativeInMaggie(): void
    {
        $event = (new GoogleEventMapper())->fromGoogle($this->googleEvent('tentative'), new Agenda());

        self::assertSame(EventStatus::Tentative, $event->getStatus());
    }

    public function testAStatusChangedInGoogleComesBackOnTheExistingEvent(): void
    {
        $mapper = new GoogleEventMapper();
        $existing = $this->maggieEvent(EventStatus::Tentative);

        $event = $mapper->fromGoogle($this->googleEvent('confirmed'), new Agenda(), $existing);

        self::assertSame($existing, $event);
        self::assertSame(EventStatus::Confirmed, $event->getStatus());

        $event = $mapper->fromGoogle($this->googleEvent('tentative'), new Agenda(), $existing);
        self::assertSame(EventStatus::Tentative, $event->getStatus());
    }

    public function testAnUnknownGoogleStatusLeavesTheEventAsItWas(): void
    {
        $existing = $this->maggieEvent(EventStatus::Tentative);

        $event = (new GoogleEventMapper())->fromGoogle($this->googleEvent('mystery'), new Agenda(), $existing);

        self::assertSame(EventStatus::Tentative, $event->getStatus());
    }

    /** @param array{string, string} $dates Google's start.date and end.date */
    private function googleDay(array $dates): GoogleEvent
    {
        $start = new EventDateTime();
        $start->setDate($dates[0]);
        $end = new EventDateTime();
        $end->setDate($dates[1]);

        $googleEvent = new GoogleEvent();
        $googleEvent->setSummary('Anniversaire');
        $googleEvent->setStart($start);
        $googleEvent->setEnd($end);

        return $googleEvent;
    }

    /**
     * Google's `end.date` is the day after the last; ours is the last one
     * (MAG-382). The one conversion of the system, on the way in…
     *
     * @return iterable<string, array{array{string, string}, string, string}>
     */
    public static function googleDays(): iterable
    {
        yield 'one day' => [['2037-01-01', '2037-01-02'], '2037-01-01', '2037-01-01'];
        yield 'three days' => [['2037-01-26', '2037-01-29'], '2037-01-26', '2037-01-28'];
        yield 'across the new year' => [['2036-12-31', '2037-01-02'], '2036-12-31', '2037-01-01'];
    }

    /** @param array{string, string} $google */
    #[\PHPUnit\Framework\Attributes\DataProvider('googleDays')]
    public function testAGoogleDayComesInAsItsDaysTheLastIncluded(array $google, string $startDate, string $endDate): void
    {
        $event = (new GoogleEventMapper())->fromGoogle($this->googleDay($google), (new Agenda())->setName('Perso'));

        self::assertTrue($event->isAllDay());
        self::assertSame($startDate, $event->getStartDate()?->format('Y-m-d'));
        self::assertSame($endDate, $event->getEndDate()?->format('Y-m-d'));
        self::assertNull($event->getStartAt());
        self::assertNull($event->getEndAt());
    }

    /**
     * …and back out, inverted, so a round trip keeps the day on its day.
     *
     * @param array{string, string} $google
     */
    #[\PHPUnit\Framework\Attributes\DataProvider('googleDays')]
    public function testADayGoesBackToGoogleAsItCameAndRoundTrips(array $google, string $startDate, string $endDate): void
    {
        $mapper = new GoogleEventMapper();
        $event = (new Event())->setSummary('Anniversaire')
            ->scheduleAllDay(new \DateTimeImmutable($startDate), new \DateTimeImmutable($endDate));

        $sent = $mapper->toGoogle($event);
        self::assertSame($google, [$sent->getStart()->getDate(), $sent->getEnd()->getDate()]);
        self::assertNull($sent->getStart()->getDateTime());

        $patch = $mapper->toGooglePatch($event, ['endDate']);
        self::assertSame($google, [$patch->getStart()->getDate(), $patch->getEnd()->getDate()]);

        $back = $mapper->fromGoogle($sent, (new Agenda())->setName('Perso'));
        self::assertSame([$startDate, $endDate], [$back->getStartDate()?->format('Y-m-d'), $back->getEndDate()?->format('Y-m-d')]);
    }

    public function testAGoogleEventWithATimeBecomingADayLosesItsInstants(): void
    {
        $mapper = new GoogleEventMapper();
        $existing = $mapper->fromGoogle($this->googleEvent('confirmed'), (new Agenda())->setName('Perso'));
        self::assertNotNull($existing->getStartAt());

        $event = $mapper->fromGoogle($this->googleDay(['2037-01-01', '2037-01-02']), (new Agenda())->setName('Perso'), $existing);

        self::assertNull($event->getStartAt());
        self::assertSame('2037-01-01', $event->getStartDate()?->format('Y-m-d'));
    }
}
