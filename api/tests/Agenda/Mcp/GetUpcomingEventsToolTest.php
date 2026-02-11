<?php

namespace App\Tests\Agenda\Mcp;

use Maggie\Agenda\Entity\Calendar;
use Maggie\Agenda\Entity\Event;
use Maggie\Agenda\Entity\EventStatus;
use Maggie\Agenda\Mcp\Tool\GetUpcomingEventsTool;
use Maggie\Agenda\Repository\EventRepository;
use Maggie\Agenda\Service\RecurrenceService;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class GetUpcomingEventsToolTest extends KernelTestCase
{
    protected function setUp(): void
    {
        self::bootKernel();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->createQuery('DELETE FROM ' . Event::class)->execute();
        $em->createQuery('DELETE FROM ' . Calendar::class)->execute();
    }

    private function createCalendarAndEvent(string $summary, \DateTimeImmutable $start, \DateTimeImmutable $end, EventStatus $status = EventStatus::Confirmed): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        // Reuse or create calendar
        $calendarRepo = $em->getRepository(Calendar::class);
        $calendar = $calendarRepo->findOneBy(['name' => 'Test']);
        if ($calendar === null) {
            $calendar = new Calendar();
            $calendar->setName('Test');
            $calendar->setIsDefault(true);
            $em->persist($calendar);
        }

        $event = new Event();
        $event->setSummary($summary);
        $event->setStartAt($start);
        $event->setEndAt($end);
        $event->setStatus($status);
        $event->setCalendar($calendar);
        $em->persist($event);
        $em->flush();
    }

    public function testReturnsUpcomingEvents(): void
    {
        $tomorrow = new \DateTimeImmutable('tomorrow 10:00');
        $this->createCalendarAndEvent('Tomorrow meeting', $tomorrow, $tomorrow->modify('+1 hour'));

        $eventRepo = self::getContainer()->get(EventRepository::class);
        $recurrenceService = self::getContainer()->get(RecurrenceService::class);

        $tool = new GetUpcomingEventsTool($eventRepo, $recurrenceService);

        $result = $tool(7);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('events', $data);
        self::assertArrayHasKey('count', $data);
        self::assertSame(1, $data['count']);
        self::assertSame('Tomorrow meeting', $data['events'][0]['summary']);
        self::assertSame('Test', $data['events'][0]['calendar']);
        self::assertSame('confirmed', $data['events'][0]['status']);
        self::assertFalse($data['events'][0]['recurring']);
    }

    public function testReturnsEmptyForNoUpcomingEvents(): void
    {
        // Create event far in the future
        $farFuture = new \DateTimeImmutable('+60 days 10:00');
        $this->createCalendarAndEvent('Far away', $farFuture, $farFuture->modify('+1 hour'));

        $eventRepo = self::getContainer()->get(EventRepository::class);
        $recurrenceService = self::getContainer()->get(RecurrenceService::class);

        $tool = new GetUpcomingEventsTool($eventRepo, $recurrenceService);

        $result = $tool(7);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(0, $data['count']);
        self::assertCount(0, $data['events']);
    }

    public function testExcludesCancelledEvents(): void
    {
        $tomorrow = new \DateTimeImmutable('tomorrow 10:00');
        $this->createCalendarAndEvent('Active', $tomorrow, $tomorrow->modify('+1 hour'));
        $this->createCalendarAndEvent('Cancelled', $tomorrow->modify('+2 hours'), $tomorrow->modify('+3 hours'), EventStatus::Cancelled);

        $eventRepo = self::getContainer()->get(EventRepository::class);
        $recurrenceService = self::getContainer()->get(RecurrenceService::class);

        $tool = new GetUpcomingEventsTool($eventRepo, $recurrenceService);

        $result = $tool(7);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(1, $data['count']);
        self::assertSame('Active', $data['events'][0]['summary']);
    }

    public function testOutputContainsAllExpectedFields(): void
    {
        $tomorrow = new \DateTimeImmutable('tomorrow 14:00');
        $this->createCalendarAndEvent('Full event', $tomorrow, $tomorrow->modify('+90 minutes'));

        $eventRepo = self::getContainer()->get(EventRepository::class);
        $recurrenceService = self::getContainer()->get(RecurrenceService::class);

        $tool = new GetUpcomingEventsTool($eventRepo, $recurrenceService);

        $result = $tool(7);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        $event = $data['events'][0];

        self::assertArrayHasKey('id', $event);
        self::assertArrayHasKey('summary', $event);
        self::assertArrayHasKey('description', $event);
        self::assertArrayHasKey('location', $event);
        self::assertArrayHasKey('allDay', $event);
        self::assertArrayHasKey('startAt', $event);
        self::assertArrayHasKey('endAt', $event);
        self::assertArrayHasKey('status', $event);
        self::assertArrayHasKey('calendar', $event);
        self::assertArrayHasKey('recurring', $event);
    }
}
