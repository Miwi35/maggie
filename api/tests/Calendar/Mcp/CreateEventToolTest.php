<?php

namespace App\Tests\Calendar\Mcp;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Mcp\Tool\CreateEventTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class CreateEventToolTest extends KernelTestCase
{
    protected function setUp(): void
    {
        self::bootKernel();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->createQuery('DELETE FROM ' . Event::class)->execute();
        $em->createQuery('DELETE FROM ' . Agenda::class)->execute();
    }

    private function getTool(): CreateEventTool
    {
        return self::getContainer()->get(CreateEventTool::class);
    }

    public function testCreateEventPersistsToDatabase(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        // Create a default agenda
        $agenda = new Agenda();
        $agenda->setName('Main');
        $agenda->setIsDefault(true);
        $em->persist($agenda);
        $em->flush();

        $tool = $this->getTool();

        $result = $tool('Team standup', '2026-03-20', '09:30', 30, 'Daily sync', 'Room A');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame('Team standup', $data['event']['summary']);
        self::assertSame('Main', $data['event']['agenda']);

        // Verify event is persisted
        $eventRepo = $em->getRepository(Event::class);
        $events = $eventRepo->findAll();
        self::assertCount(1, $events);
        self::assertSame('Team standup', $events[0]->getSummary());
        self::assertSame('Daily sync', $events[0]->getDescription());
        self::assertSame('Room A', $events[0]->getLocation());
    }

    public function testCreateEventReturnsErrorWithoutDefaultAgenda(): void
    {
        $tool = $this->getTool();

        $result = $tool('Event without calendar', '2026-03-20');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertSame('No agenda found.', $data['error']);
    }

    public function testCreateEventUsesDefaultDuration(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        $agenda = new Agenda();
        $agenda->setName('Default');
        $agenda->setIsDefault(true);
        $em->persist($agenda);
        $em->flush();

        $tool = $this->getTool();

        $result = $tool('Meeting', '2026-03-20', '10:00');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);

        // Default duration is 60 minutes
        $start = new \DateTimeImmutable($data['event']['startAt']);
        $end = new \DateTimeImmutable($data['event']['endAt']);
        $diff = $start->diff($end);
        self::assertSame(60, $diff->i + $diff->h * 60);
    }
}
