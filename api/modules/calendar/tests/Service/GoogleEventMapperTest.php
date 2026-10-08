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
}
