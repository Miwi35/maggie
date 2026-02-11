<?php

namespace App\Tests\Agenda\Service;

use Maggie\Agenda\Entity\Calendar;
use Maggie\Agenda\Entity\Event;
use Maggie\Agenda\Repository\EventRepository;
use Maggie\Agenda\Service\ConflictDetectionService;
use Maggie\Agenda\Service\RecurrenceService;
use PHPUnit\Framework\MockObject\MockObject;
use PHPUnit\Framework\TestCase;

class ConflictDetectionServiceTest extends TestCase
{
    private EventRepository&MockObject $eventRepository;
    private RecurrenceService&MockObject $recurrenceService;
    private ConflictDetectionService $conflictDetectionService;
    private Calendar $calendar;

    protected function setUp(): void
    {
        $this->eventRepository = $this->createMock(EventRepository::class);
        $this->recurrenceService = $this->createMock(RecurrenceService::class);
        $this->conflictDetectionService = new ConflictDetectionService(
            $this->eventRepository,
            $this->recurrenceService,
        );

        $this->calendar = new Calendar();
        $this->calendar->setName('Test');
    }

    public function testFindConflictsReturnsOverlappingEvents(): void
    {
        $overlapping = new Event();
        $overlapping->setSummary('Overlapping meeting');
        $overlapping->setStartAt(new \DateTimeImmutable('2026-03-01T09:30:00Z'));
        $overlapping->setEndAt(new \DateTimeImmutable('2026-03-01T10:30:00Z'));
        $overlapping->setCalendar($this->calendar);

        $rangeStart = new \DateTimeImmutable('2026-03-01T10:00:00Z');
        $rangeEnd = new \DateTimeImmutable('2026-03-01T11:00:00Z');

        $this->eventRepository
            ->method('findByDateRange')
            ->with($rangeStart, $rangeEnd)
            ->willReturn([$overlapping]);

        // expandAll passes events through unchanged
        $this->recurrenceService
            ->method('expandAll')
            ->with([$overlapping], $rangeStart, $rangeEnd)
            ->willReturn([$overlapping]);

        $conflicts = $this->conflictDetectionService->findConflicts($rangeStart, $rangeEnd);

        self::assertCount(1, $conflicts);
        self::assertSame($overlapping, $conflicts[0]);
    }

    public function testFindConflictsExcludesSpecifiedEvent(): void
    {
        $existing = new Event();
        $existing->setSummary('Existing meeting');
        $existing->setStartAt(new \DateTimeImmutable('2026-03-01T10:00:00Z'));
        $existing->setEndAt(new \DateTimeImmutable('2026-03-01T11:00:00Z'));
        $existing->setCalendar($this->calendar);

        $rangeStart = new \DateTimeImmutable('2026-03-01T10:00:00Z');
        $rangeEnd = new \DateTimeImmutable('2026-03-01T11:00:00Z');

        $this->eventRepository
            ->method('findByDateRange')
            ->with($rangeStart, $rangeEnd)
            ->willReturn([$existing]);

        $this->recurrenceService
            ->method('expandAll')
            ->with([$existing], $rangeStart, $rangeEnd)
            ->willReturn([$existing]);

        // Exclude the event itself (simulates updating an existing event)
        $conflicts = $this->conflictDetectionService->findConflicts($rangeStart, $rangeEnd, $existing);

        self::assertCount(0, $conflicts);
    }

    public function testFindConflictsSkipsAllDayEvents(): void
    {
        $allDayEvent = new Event();
        $allDayEvent->setSummary('All day conference');
        $allDayEvent->setStartAt(new \DateTimeImmutable('2026-03-01T00:00:00Z'));
        $allDayEvent->setEndAt(new \DateTimeImmutable('2026-03-01T23:59:59Z'));
        $allDayEvent->setAllDay(true);
        $allDayEvent->setCalendar($this->calendar);

        $rangeStart = new \DateTimeImmutable('2026-03-01T10:00:00Z');
        $rangeEnd = new \DateTimeImmutable('2026-03-01T11:00:00Z');

        $this->eventRepository
            ->method('findByDateRange')
            ->with($rangeStart, $rangeEnd)
            ->willReturn([$allDayEvent]);

        $this->recurrenceService
            ->method('expandAll')
            ->with([$allDayEvent], $rangeStart, $rangeEnd)
            ->willReturn([$allDayEvent]);

        $conflicts = $this->conflictDetectionService->findConflicts($rangeStart, $rangeEnd);

        self::assertCount(0, $conflicts);
    }

    public function testHasConflictsReturnsTrue(): void
    {
        $overlapping = new Event();
        $overlapping->setSummary('Blocking meeting');
        $overlapping->setStartAt(new \DateTimeImmutable('2026-03-01T10:00:00Z'));
        $overlapping->setEndAt(new \DateTimeImmutable('2026-03-01T11:00:00Z'));
        $overlapping->setCalendar($this->calendar);

        $rangeStart = new \DateTimeImmutable('2026-03-01T10:30:00Z');
        $rangeEnd = new \DateTimeImmutable('2026-03-01T11:30:00Z');

        $this->eventRepository
            ->method('findByDateRange')
            ->with($rangeStart, $rangeEnd)
            ->willReturn([$overlapping]);

        $this->recurrenceService
            ->method('expandAll')
            ->with([$overlapping], $rangeStart, $rangeEnd)
            ->willReturn([$overlapping]);

        self::assertTrue($this->conflictDetectionService->hasConflicts($rangeStart, $rangeEnd));
    }

    public function testHasConflictsReturnsFalseWhenNoOverlap(): void
    {
        $nonOverlapping = new Event();
        $nonOverlapping->setSummary('Earlier meeting');
        $nonOverlapping->setStartAt(new \DateTimeImmutable('2026-03-01T08:00:00Z'));
        $nonOverlapping->setEndAt(new \DateTimeImmutable('2026-03-01T09:00:00Z'));
        $nonOverlapping->setCalendar($this->calendar);

        $rangeStart = new \DateTimeImmutable('2026-03-01T10:00:00Z');
        $rangeEnd = new \DateTimeImmutable('2026-03-01T11:00:00Z');

        $this->eventRepository
            ->method('findByDateRange')
            ->with($rangeStart, $rangeEnd)
            ->willReturn([$nonOverlapping]);

        $this->recurrenceService
            ->method('expandAll')
            ->with([$nonOverlapping], $rangeStart, $rangeEnd)
            ->willReturn([$nonOverlapping]);

        self::assertFalse($this->conflictDetectionService->hasConflicts($rangeStart, $rangeEnd));
    }
}
