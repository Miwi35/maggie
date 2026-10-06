<?php

namespace Maggie\Calendar\Tests\Service;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\RecurrenceService;
use PHPUnit\Framework\MockObject\MockObject;
use PHPUnit\Framework\TestCase;
use Psr\Log\LoggerInterface;

class RecurrenceServiceTest extends TestCase
{
    private EventRepository&MockObject $eventRepository;
    private LoggerInterface&MockObject $logger;
    private RecurrenceService $recurrenceService;

    protected function setUp(): void
    {
        $this->eventRepository = $this->createMock(EventRepository::class);
        $this->logger = $this->createMock(LoggerInterface::class);
        $this->recurrenceService = new RecurrenceService($this->eventRepository, $this->logger);
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

    /**
     * MAG-212: a weekly 18:00 Paris series stays at 18:00 Paris across the autumn
     * clock change (Sunday 25 Oct 2026), and an exception is matched on that local
     * time, whatever the offset the database hands back.
     */
    public function testExpandOccurrencesKeepsLocalTimeAcrossDstChange(): void
    {
        $event = $this->weeklyLessonInParis();
        $this->eventRepository->method('findExceptionsForRecurringEvent')->willReturn([]);

        $result = $this->recurrenceService->expandOccurrences(
            $event,
            new \DateTimeImmutable('2026-10-01T00:00:00Z'),
            new \DateTimeImmutable('2026-11-30T00:00:00Z'),
        );

        self::assertSame(
            [
                '2026-10-18T18:00:00+02:00',
                '2026-10-25T18:00:00+01:00',
                '2026-11-01T18:00:00+01:00',
                '2026-11-08T18:00:00+01:00',
            ],
            array_map(
                fn (Event $occurrence) => $occurrence->getStartAt()->setTimezone(new \DateTimeZone('Europe/Paris'))->format('Y-m-d\TH:i:sP'),
                $result,
            ),
        );
    }

    public function testCancelledExceptionOnTheDayClocksGoBackRemovesThatOccurrence(): void
    {
        $event = $this->weeklyLessonInParis();

        $cancelled = new Event();
        $cancelled->setSummary('Cours de piano');
        $cancelled->setStartAt(new \DateTimeImmutable('2026-10-25T17:00:00Z'));
        $cancelled->setEndAt(new \DateTimeImmutable('2026-10-25T17:00:00Z'));
        $cancelled->setOriginalStartAt(new \DateTimeImmutable('2026-10-25T17:00:00Z'));
        $cancelled->setStatus(EventStatus::Cancelled);
        $this->eventRepository->method('findExceptionsForRecurringEvent')->willReturn([$cancelled]);

        $result = $this->recurrenceService->expandOccurrences(
            $event,
            new \DateTimeImmutable('2026-10-01T00:00:00Z'),
            new \DateTimeImmutable('2026-11-30T00:00:00Z'),
        );

        self::assertSame(
            ['2026-10-18T16:00:00Z', '2026-11-01T17:00:00Z', '2026-11-08T17:00:00Z'],
            array_map(
                fn (Event $occurrence) => $occurrence->getStartAt()->setTimezone(new \DateTimeZone('UTC'))->format('Y-m-d\TH:i:s\Z'),
                $result,
            ),
        );
    }

    public function testUnresolvableTimeZoneFallsBackOnTheDefaultZoneAndIsLogged(): void
    {
        $event = $this->weeklyLessonInParis();
        $event->setTimeZone('Mars/Olympus');
        $this->eventRepository->method('findExceptionsForRecurringEvent')->willReturn([]);

        $this->logger
            ->expects(self::once())
            ->method('error')
            ->with(
                self::anything(),
                self::callback(fn (array $context) => 'event_time_zone_fallback' === $context['event']
                    && 'Mars/Olympus' === $context['timeZone']),
            );

        $result = $this->recurrenceService->expandOccurrences(
            $event,
            new \DateTimeImmutable('2026-10-01T00:00:00Z'),
            new \DateTimeImmutable('2026-11-30T00:00:00Z'),
        );

        // Expanded on Europe/Paris, the column's default: 18:00 stays 18:00 across the clock change.
        self::assertSame(
            [
                '2026-10-18T18:00:00+02:00',
                '2026-10-25T18:00:00+01:00',
                '2026-11-01T18:00:00+01:00',
                '2026-11-08T18:00:00+01:00',
            ],
            array_map(
                fn (Event $occurrence) => $occurrence->getStartAt()->setTimezone(new \DateTimeZone('Europe/Paris'))->format('Y-m-d\TH:i:sP'),
                $result,
            ),
        );
    }

    public function testResolvableTimeZoneIsNotLogged(): void
    {
        $event = $this->weeklyLessonInParis();
        $this->eventRepository->method('findExceptionsForRecurringEvent')->willReturn([]);
        $this->logger->expects(self::never())->method('error');

        $this->recurrenceService->expandOccurrences(
            $event,
            new \DateTimeImmutable('2026-10-01T00:00:00Z'),
            new \DateTimeImmutable('2026-11-30T00:00:00Z'),
        );
    }

    private function weeklyLessonInParis(): Event
    {
        $agenda = new Agenda();
        $agenda->setName('Perso');

        // Stored the way the database returns it: an instant, here in UTC.
        $event = new Event();
        $event->setSummary('Cours de piano');
        $event->setStartAt(new \DateTimeImmutable('2026-10-18T16:00:00Z'));
        $event->setEndAt(new \DateTimeImmutable('2026-10-18T17:00:00Z'));
        $event->setTimeZone('Europe/Paris');
        $event->setRrule('FREQ=WEEKLY;COUNT=4');
        $event->setAgenda($agenda);

        return $event;
    }
}
