<?php

namespace Maggie\Calendar\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\UpdateEventCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateEventHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('UpdateEventHandlerTest.yaml');
    }

    private function dispatch(UpdateEventCommand $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function reload(): Event
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Event::class)->find($this->getFixture('event_full')->getId());
    }

    public function testClearingDescriptionAndLocation(): void
    {
        $this->dispatch(new UpdateEventCommand(
            eventId: (string) $this->getFixture('event_full')->getId(),
            clearFields: ['description', 'location'],
        ));

        $event = $this->reload();
        self::assertNull($event->getDescription());
        self::assertNull($event->getLocation());
        self::assertSame('Weekly sync', $event->getSummary());
        self::assertSame('FREQ=WEEKLY', $event->getRrule());
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testClearingRruleMakesTheEventNonRecurring(): void
    {
        $this->dispatch(new UpdateEventCommand(
            eventId: (string) $this->getFixture('event_full')->getId(),
            clearFields: ['rrule'],
        ));

        $event = $this->reload();
        self::assertNull($event->getRrule());
        self::assertSame('Bring slides', $event->getDescription());
        self::assertSame('Room 4', $event->getLocation());
    }

    public function testNullFieldsWithoutClearAreLeftUntouched(): void
    {
        $this->dispatch(new UpdateEventCommand(
            eventId: (string) $this->getFixture('event_full')->getId(),
            summary: 'Renamed',
        ));

        $event = $this->reload();
        self::assertSame('Renamed', $event->getSummary());
        self::assertSame('Bring slides', $event->getDescription());
        self::assertSame('Room 4', $event->getLocation());
        self::assertSame('FREQ=WEEKLY', $event->getRrule());
    }

    public function testUnknownEventFails(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('Event not found');

        $this->dispatch(new UpdateEventCommand(eventId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clearFields: ['description']));
    }
}
