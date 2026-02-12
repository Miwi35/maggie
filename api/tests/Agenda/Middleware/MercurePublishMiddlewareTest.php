<?php

namespace App\Tests\Agenda\Middleware;

use Maggie\Agenda\Entity\Calendar;
use Maggie\Agenda\Entity\Event;
use Maggie\Agenda\Message\CreateCalendarCommand;
use Maggie\Agenda\Message\CreateEventCommand;
use Maggie\Agenda\Message\DeleteCalendarCommand;
use Maggie\Agenda\Message\DeleteEventCommand;
use Maggie\Agenda\Message\UpdateCalendarCommand;
use Maggie\Agenda\Message\UpdateEventCommand;
use Maggie\Agenda\Middleware\MercurePublishMiddleware;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Middleware\MiddlewareInterface;
use Symfony\Component\Messenger\Middleware\StackInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Uid\Uuid;

class MercurePublishMiddlewareTest extends TestCase
{
    private HubInterface $hub;
    /** @var Update[] */
    private array $publishedUpdates = [];

    protected function setUp(): void
    {
        $this->publishedUpdates = [];
        $this->hub = $this->createMock(HubInterface::class);
        $this->hub->method('publish')->willReturnCallback(function (Update $update) {
            $this->publishedUpdates[] = $update;
            return 'urn:uuid:' . Uuid::v7();
        });
    }

    private function createPassthroughStack(object $result = null): StackInterface
    {
        $next = $this->createMock(MiddlewareInterface::class);
        $next->method('handle')->willReturnCallback(
            function (Envelope $envelope) use ($result) {
                if ($result !== null) {
                    return $envelope->with(new HandledStamp($result, 'handler'));
                }
                return $envelope->with(new HandledStamp(null, 'handler'));
            }
        );

        $stack = $this->createMock(StackInterface::class);
        $stack->method('next')->willReturn($next);

        return $stack;
    }

    public function testCreateEventPublishesToMercure(): void
    {
        $event = new Event();
        $event->setSummary('Meeting');
        $event->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));

        $middleware = new MercurePublishMiddleware($this->hub);
        $envelope = new Envelope(new CreateEventCommand('Meeting', $event->getStartAt(), $event->getEndAt()));

        $middleware->handle($envelope, $this->createPassthroughStack($event));

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Meeting', $data['summary']);
        self::assertStringContainsString('/api/events/', $data['@id']);
    }

    public function testUpdateEventPublishesToMercure(): void
    {
        $event = new Event();
        $event->setSummary('Updated Meeting');
        $event->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));

        $middleware = new MercurePublishMiddleware($this->hub);
        $envelope = new Envelope(new UpdateEventCommand(eventId: (string) $event->getId()));

        $middleware->handle($envelope, $this->createPassthroughStack($event));

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Updated Meeting', $data['summary']);
    }

    public function testDeleteEventPublishesToMercure(): void
    {
        $eventId = (string) Uuid::v7();

        $middleware = new MercurePublishMiddleware($this->hub);
        $envelope = new Envelope(new DeleteEventCommand(eventId: $eventId));

        $middleware->handle($envelope, $this->createPassthroughStack());

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('/api/events/' . $eventId, $data['@id']);
        self::assertTrue($data['deleted']);
    }

    public function testCreateCalendarPublishesToMercure(): void
    {
        $calendar = new Calendar();
        $calendar->setName('Work');
        $calendar->setColor('#ff0000');

        $middleware = new MercurePublishMiddleware($this->hub);
        $envelope = new Envelope(new CreateCalendarCommand(name: 'Work', color: '#ff0000'));

        $middleware->handle($envelope, $this->createPassthroughStack($calendar));

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Work', $data['name']);
        self::assertSame('#ff0000', $data['color']);
        self::assertStringContainsString('/api/calendars/', $data['@id']);
    }

    public function testUpdateCalendarPublishesToMercure(): void
    {
        $calendar = new Calendar();
        $calendar->setName('Personal');

        $middleware = new MercurePublishMiddleware($this->hub);
        $envelope = new Envelope(new UpdateCalendarCommand(calendarId: (string) $calendar->getId()));

        $middleware->handle($envelope, $this->createPassthroughStack($calendar));

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Personal', $data['name']);
    }

    public function testDeleteCalendarPublishesToMercure(): void
    {
        $calendarId = (string) Uuid::v7();

        $middleware = new MercurePublishMiddleware($this->hub);
        $envelope = new Envelope(new DeleteCalendarCommand(calendarId: $calendarId));

        $middleware->handle($envelope, $this->createPassthroughStack());

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('/api/calendars/' . $calendarId, $data['@id']);
        self::assertTrue($data['deleted']);
    }

    public function testUnrelatedMessageDoesNotPublish(): void
    {
        $middleware = new MercurePublishMiddleware($this->hub);
        $envelope = new Envelope(new \stdClass());

        $middleware->handle($envelope, $this->createPassthroughStack());

        self::assertCount(0, $this->publishedUpdates);
    }
}
