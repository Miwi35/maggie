<?php

namespace Maggie\Calendar\Tests\Entity;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\EventStatus;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Uid\Ulid;

class EventTest extends TestCase
{
    private Agenda $agenda;

    protected function setUp(): void
    {
        $this->agenda = new Agenda();
        $this->agenda->setName('Test');
    }

    public function testIsRecurringReturnsTrueWithRrule(): void
    {
        $event = new Event();
        $event->setSummary('Weekly sync');
        $event->setStartAt(new \DateTimeImmutable('2026-03-01T10:00:00Z'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-01T11:00:00Z'));
        $event->setAgenda($this->agenda);
        $event->setRrule('FREQ=WEEKLY;COUNT=10');

        self::assertTrue($event->isRecurring());
    }

    public function testIsRecurringReturnsFalseWithoutRrule(): void
    {
        $event = new Event();
        $event->setSummary('One-time meeting');
        $event->setStartAt(new \DateTimeImmutable('2026-03-01T10:00:00Z'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-01T11:00:00Z'));
        $event->setAgenda($this->agenda);

        self::assertFalse($event->isRecurring());
    }

    public function testIsExceptionReturnsTrueWithRecurringEvent(): void
    {
        $parent = new Event();
        $parent->setSummary('Weekly sync');
        $parent->setStartAt(new \DateTimeImmutable('2026-03-01T10:00:00Z'));
        $parent->setEndAt(new \DateTimeImmutable('2026-03-01T11:00:00Z'));
        $parent->setRrule('FREQ=WEEKLY;COUNT=10');
        $parent->setAgenda($this->agenda);

        $exception = new Event();
        $exception->setSummary('Weekly sync (moved)');
        $exception->setStartAt(new \DateTimeImmutable('2026-03-08T14:00:00Z'));
        $exception->setEndAt(new \DateTimeImmutable('2026-03-08T15:00:00Z'));
        $exception->setAgenda($this->agenda);
        $exception->setRecurringEvent($parent);
        $exception->setOriginalStartAt(new \DateTimeImmutable('2026-03-08T10:00:00Z'));

        self::assertTrue($exception->isException());
    }

    public function testIsExceptionReturnsFalseWithoutRecurringEvent(): void
    {
        $event = new Event();
        $event->setSummary('Standalone event');
        $event->setStartAt(new \DateTimeImmutable('2026-03-01T10:00:00Z'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-01T11:00:00Z'));
        $event->setAgenda($this->agenda);

        self::assertFalse($event->isException());
    }

    public function testDefaultValues(): void
    {
        $event = new Event();

        self::assertSame(EventStatus::Confirmed, $event->getStatus());
        self::assertFalse($event->isAllDay());
        self::assertSame('Europe/Paris', $event->getTimeZone());
    }

    public function testIdIsUlid(): void
    {
        $event = new Event();

        self::assertInstanceOf(Ulid::class, $event->getId());
    }
}
