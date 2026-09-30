<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Mcp\Tool\UpdateEventTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class UpdateEventToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('UpdateEventToolTest.yaml');
        $this->loginFixtureUser();
    }

    private function tool(): UpdateEventTool
    {
        return self::getContainer()->get(UpdateEventTool::class);
    }

    private function id(): string
    {
        return (string) $this->getFixture('event_full')->getId();
    }

    private function reload(): Event
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Event::class)->find($this->getFixture('event_full')->getId());
    }

    public function testClearEmptiesOptionalFields(): void
    {
        $data = json_decode(
            ($this->tool())($this->id(), clear: ['description', 'location', 'rrule', 'summary']),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        $event = $this->reload();
        self::assertNull($event->getDescription());
        self::assertNull($event->getLocation());
        self::assertNull($event->getRrule());
        self::assertSame('Weekly sync', $event->getSummary(), 'Required fields cannot be cleared');
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testWithoutClearNullFieldsAreLeftUntouched(): void
    {
        $data = json_decode(($this->tool())($this->id(), title: 'Renamed'), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        $event = $this->reload();
        self::assertSame('Renamed', $event->getSummary());
        self::assertSame('Bring slides', $event->getDescription());
        self::assertSame('Room 4', $event->getLocation());
        self::assertSame('FREQ=WEEKLY', $event->getRrule());
    }

    public function testUnknownEventReturnsAnError(): void
    {
        $data = json_decode(($this->tool())('01ARZ3NDEKTSV4RRFFQ69G5FAV', clear: ['description']), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }
}
