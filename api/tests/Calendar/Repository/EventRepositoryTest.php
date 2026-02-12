<?php

namespace App\Tests\Calendar\Repository;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\EventStatus;
use Maggie\Calendar\Repository\EventRepository;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class EventRepositoryTest extends KernelTestCase
{
    private EventRepository $repository;

    protected function setUp(): void
    {
        self::bootKernel();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        // Clean tables
        $em->createQuery('DELETE FROM ' . Event::class)->execute();
        $em->createQuery('DELETE FROM ' . Agenda::class)->execute();

        $this->repository = $em->getRepository(Event::class);
    }

    private function createAgenda(): Agenda
    {
        $agenda = new Agenda();
        $agenda->setName('Test Agenda');
        $agenda->setIsDefault(true);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->persist($agenda);
        $em->flush();

        return $agenda;
    }

    private function createEvent(
        Agenda $agenda,
        string $summary,
        \DateTimeImmutable $start,
        \DateTimeImmutable $end,
        EventStatus $status = EventStatus::Confirmed,
        ?string $rrule = null,
    ): Event {
        $event = new Event();
        $event->setSummary($summary);
        $event->setStartAt($start);
        $event->setEndAt($end);
        $event->setStatus($status);
        $event->setAgenda($agenda);

        if ($rrule !== null) {
            $event->setRrule($rrule);
        }

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->persist($event);
        $em->flush();

        return $event;
    }

    public function testFindByDateRangeReturnsEventsInRange(): void
    {
        $agenda = $this->createAgenda();

        $this->createEvent($agenda, 'In range', new \DateTimeImmutable('2026-03-15 10:00'), new \DateTimeImmutable('2026-03-15 11:00'));
        $this->createEvent($agenda, 'Out of range', new \DateTimeImmutable('2026-04-01 10:00'), new \DateTimeImmutable('2026-04-01 11:00'));

        $results = $this->repository->findByDateRange(
            new \DateTimeImmutable('2026-03-01 00:00'),
            new \DateTimeImmutable('2026-03-31 23:59'),
        );

        self::assertCount(1, $results);
        self::assertSame('In range', $results[0]->getSummary());
    }

    public function testFindByDateRangeExcludesCancelledEvents(): void
    {
        $agenda = $this->createAgenda();

        $this->createEvent($agenda, 'Active', new \DateTimeImmutable('2026-03-15 10:00'), new \DateTimeImmutable('2026-03-15 11:00'));
        $this->createEvent($agenda, 'Cancelled', new \DateTimeImmutable('2026-03-15 14:00'), new \DateTimeImmutable('2026-03-15 15:00'), EventStatus::Cancelled);

        $results = $this->repository->findByDateRange(
            new \DateTimeImmutable('2026-03-01 00:00'),
            new \DateTimeImmutable('2026-03-31 23:59'),
        );

        self::assertCount(1, $results);
        self::assertSame('Active', $results[0]->getSummary());
    }

    public function testFindByDateRangeIncludesRecurringMasters(): void
    {
        $agenda = $this->createAgenda();

        $this->createEvent($agenda, 'Recurring', new \DateTimeImmutable('2026-01-01 09:00'), new \DateTimeImmutable('2026-01-01 10:00'), rrule: 'FREQ=WEEKLY');

        $results = $this->repository->findByDateRange(
            new \DateTimeImmutable('2026-06-01 00:00'),
            new \DateTimeImmutable('2026-06-30 23:59'),
        );

        // Recurring masters are always included regardless of date range
        self::assertCount(1, $results);
        self::assertSame('Recurring', $results[0]->getSummary());
    }

    public function testFindUpcomingReturnsEventsWithinDays(): void
    {
        $agenda = $this->createAgenda();

        $tomorrow = new \DateTimeImmutable('tomorrow 10:00');
        $this->createEvent($agenda, 'Soon', $tomorrow, $tomorrow->modify('+1 hour'));

        $farFuture = new \DateTimeImmutable('+30 days 10:00');
        $this->createEvent($agenda, 'Far away', $farFuture, $farFuture->modify('+1 hour'));

        $results = $this->repository->findUpcoming(7);

        self::assertCount(1, $results);
        self::assertSame('Soon', $results[0]->getSummary());
    }

    public function testFindExceptionsForRecurringEvent(): void
    {
        $agenda = $this->createAgenda();

        $parent = $this->createEvent($agenda, 'Weekly meeting', new \DateTimeImmutable('2026-01-06 09:00'), new \DateTimeImmutable('2026-01-06 10:00'), rrule: 'FREQ=WEEKLY');

        // Create an exception instance
        $exception = new Event();
        $exception->setSummary('Modified meeting');
        $exception->setStartAt(new \DateTimeImmutable('2026-01-13 10:00'));
        $exception->setEndAt(new \DateTimeImmutable('2026-01-13 11:00'));
        $exception->setOriginalStartAt(new \DateTimeImmutable('2026-01-13 09:00'));
        $exception->setRecurringEvent($parent);
        $exception->setAgenda($agenda);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->persist($exception);
        $em->flush();

        $exceptions = $this->repository->findExceptionsForRecurringEvent($parent);

        self::assertCount(1, $exceptions);
        self::assertSame('Modified meeting', $exceptions[0]->getSummary());
    }
}
