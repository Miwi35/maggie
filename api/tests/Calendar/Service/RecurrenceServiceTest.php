<?php

namespace App\Tests\Calendar\Service;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\RecurrenceService;
use PHPUnit\Framework\MockObject\MockObject;
use PHPUnit\Framework\TestCase;

class RecurrenceServiceTest extends TestCase
{
    private EventRepository&MockObject $eventRepository;
    private RecurrenceService $recurrenceService;

    protected function setUp(): void
    {
        $this->eventRepository = $this->createMock(EventRepository::class);
        $this->recurrenceService = new RecurrenceService($this->eventRepository);
    }

    public function testExpandOccurrencesReturnsSelfForNonRecurring(): void
    {
        $agenda = new Agenda();
        $agenda->setName('Test Agenda');

        $event = new Event();
        $event->setSummary('One-time meeting');
        $event->setStartAt(new \DateTimeImmutable('2026-03-01T10:00:00Z'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-01T11:00:00Z'));
        $event->setAgenda($agenda);

        // No rrule set, so isRecurring() returns false
        $rangeStart = new \DateTimeImmutable('2026-03-01T00:00:00Z');
        $rangeEnd = new \DateTimeImmutable('2026-03-31T23:59:59Z');

        $result = $this->recurrenceService->expandOccurrences($event, $rangeStart, $rangeEnd);

        self::assertCount(1, $result);
        self::assertSame($event, $result[0]);
    }

    public function testExpandOccurrencesExpandsWeeklyRrule(): void
    {
        $agenda = new Agenda();
        $agenda->setName('Test Agenda');

        $event = new Event();
        $event->setSummary('Weekly standup');
        $event->setStartAt(new \DateTimeImmutable('2026-03-02T09:00:00Z'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-02T09:30:00Z'));
        $event->setRrule('FREQ=WEEKLY;COUNT=4');
        $event->setAgenda($agenda);

        // Mock: no exception instances for this recurring event
        $this->eventRepository
            ->method('findExceptionsForRecurringEvent')
            ->with($event)
            ->willReturn([]);

        // Range wide enough to cover all 4 occurrences (4 weeks from start)
        $rangeStart = new \DateTimeImmutable('2026-03-01T00:00:00Z');
        $rangeEnd = new \DateTimeImmutable('2026-04-30T23:59:59Z');

        $result = $this->recurrenceService->expandOccurrences($event, $rangeStart, $rangeEnd);

        self::assertCount(4, $result);

        // Each occurrence should be exactly 1 week apart
        $expectedDates = [
            '2026-03-02T09:00:00+00:00',
            '2026-03-09T09:00:00+00:00',
            '2026-03-16T09:00:00+00:00',
            '2026-03-23T09:00:00+00:00',
        ];

        foreach ($result as $i => $occurrence) {
            self::assertSame(
                $expectedDates[$i],
                $occurrence->getStartAt()->format('Y-m-d\TH:i:sP'),
                sprintf('Occurrence %d should start at %s', $i, $expectedDates[$i]),
            );
            // Each occurrence should preserve the 30-minute duration
            self::assertSame(
                '30',
                $occurrence->getStartAt()->diff($occurrence->getEndAt())->format('%i'),
                sprintf('Occurrence %d should have a 30-minute duration', $i),
            );
            self::assertSame('Weekly standup', $occurrence->getSummary());
        }
    }
}
