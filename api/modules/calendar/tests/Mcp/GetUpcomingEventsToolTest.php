<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Mcp\Tool\GetUpcomingEventsTool;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\RecurrenceService;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class GetUpcomingEventsToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
    }

    private function getTool(): GetUpcomingEventsTool
    {
        return new GetUpcomingEventsTool(
            self::getContainer()->get(EventRepository::class),
            self::getContainer()->get(RecurrenceService::class),
        );
    }

    public function testReturnsUpcomingEvents(): void
    {
        $this->loadFixtures('GetUpcomingEventsToolTest.yaml');
        $this->loginFixtureUser();

        $result = $this->getTool()(7);

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
        $this->loadFixtures('GetUpcomingEventsToolTest.yaml');
        $this->loginFixtureUser();

        $result = $this->getTool()(7);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        // Far-future event is excluded from upcoming results
        $summaries = array_map(fn($e) => $e['summary'], $data['events']);
        self::assertNotContains('Far away', $summaries);
    }

    public function testExcludesCancelledEvents(): void
    {
        $this->loadFixtures('GetUpcomingEventsToolTest.yaml');
        $this->loginFixtureUser();

        $result = $this->getTool()(7);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(1, $data['count']);
        self::assertSame('Tomorrow meeting', $data['events'][0]['summary']);
    }

    public function testOutputContainsAllExpectedFields(): void
    {
        $this->loadFixtures('GetUpcomingEventsToolTest.yaml');
        $this->loginFixtureUser();

        $result = $this->getTool()(7);

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
