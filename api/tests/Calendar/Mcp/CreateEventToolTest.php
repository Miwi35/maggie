<?php

namespace App\Tests\Calendar\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Mcp\Tool\CreateEventTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class CreateEventToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function getTool(): CreateEventTool
    {
        return self::getContainer()->get(CreateEventTool::class);
    }

    public function testCreateEventPersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('CreateEventToolTest.yaml');

        $tool = $this->getTool();

        $result = $tool('Team standup', '2026-03-20', '09:30', 30, 'Daily sync', 'Room A');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame('Team standup', $data['event']['summary']);
        self::assertSame('Main', $data['event']['agenda']);

        // DB persistence
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $events = $em->getRepository(Event::class)->findAll();
        self::assertCount(1, $events);
        self::assertSame('Team standup', $events[0]->getSummary());
        self::assertSame('Daily sync', $events[0]->getDescription());
        self::assertSame('Room A', $events[0]->getLocation());

        // Mercure publication
        $this->assertMercureUpdatePublished('/events/');

        // Elasticsearch indexation
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testCreateEventReturnsErrorWithoutDefaultAgenda(): void
    {
        $this->purgeDatabase();

        $tool = $this->getTool();

        $result = $tool('Event without calendar', '2026-03-20');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertSame('No agenda found.', $data['error']);
    }

    public function testCreateEventUsesDefaultDuration(): void
    {
        $this->loadFixtures('CreateEventToolTest.yaml');

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
