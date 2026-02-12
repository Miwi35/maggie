<?php

namespace App\Tests\Calendar\Mcp;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\EventStatus;
use Maggie\Calendar\Mcp\Tool\GetUpcomingEventsTool;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\RecurrenceService;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class GetUpcomingEventsToolTest extends KernelTestCase
{
    protected function setUp(): void
    {
        self::bootKernel();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->createQuery('DELETE FROM ' . Event::class)->execute();
        $em->createQuery('DELETE FROM ' . Agenda::class)->execute();
    }

    private function createAgendaAndEvent(string $summary, \DateTimeImmutable $start, \DateTimeImmutable $end, EventStatus $status = EventStatus::Confirmed): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        // Reuse or create agenda
        $agendaRepo = $em->getRepository(Agenda::class);
        $agenda = $agendaRepo->findOneBy(['name' => 'Test']);
        if ($agenda === null) {
            $agenda = new Agenda();
            $agenda->setName('Test');
            $agenda->setIsDefault(true);
            $em->persist($agenda);
        }

        $event = new Event();
        $event->setSummary($summary);
        $event->setStartAt($start);
        $event->setEndAt($end);
        $event->setStatus($status);
        $event->setAgenda($agenda);
        $em->persist($event);
        $em->flush();
    }

    public function testReturnsUpcomingEvents(): void
    {
        $tomorrow = new \DateTimeImmutable('tomorrow 10:00');
        $this->createAgendaAndEvent('Tomorrow meeting', $tomorrow, $tomorrow->modify('+1 hour'));

        $eventRepo = self::getContainer()->get(EventRepository::class);
        $recurrenceService = self::getContainer()->get(RecurrenceService::class);

        $tool = new GetUpcomingEventsTool($eventRepo, $recurrenceService);

        $result = $tool(7);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('events', $data);
        self::assertArrayHasKey('count', $data);
        self::assertSame(1, $data['count']);
        self::assertSame('Tomorrow meeting', $data['events'][0]['summary']);
        self::assertSame('Test', $data['events'][0]['agenda']);
        self::assertSame('confirmed', $data['events'][0]['status']);
        self::assertFalse($data['events'][0]['recurring']);
    }

    public function testReturnsEmptyForNoUpcomingEvents(): void
    {
        // Create event far in the future
        $farFuture = new \DateTimeImmutable('+60 days 10:00');
        $this->createAgendaAndEvent('Far away', $farFuture, $farFuture->modify('+1 hour'));

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
        $this->createAgendaAndEvent('Active', $tomorrow, $tomorrow->modify('+1 hour'));
        $this->createAgendaAndEvent('Cancelled', $tomorrow->modify('+2 hours'), $tomorrow->modify('+3 hours'), EventStatus::Cancelled);

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
        $this->createAgendaAndEvent('Full event', $tomorrow, $tomorrow->modify('+90 minutes'));

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
        self::assertArrayHasKey('agenda', $event);
        self::assertArrayHasKey('recurring', $event);
    }
}
