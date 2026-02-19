<?php

namespace App\Tests\Core\Elasticsearch;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Entity\TaskCriticality;
use Maggie\Calendar\Entity\TaskPriority;
use Maggie\Calendar\Message\CreateEventCommand;
use Maggie\Calendar\Message\DeleteEventCommand;
use Maggie\Calendar\Message\UpdateEventCommand;
use Maggie\Calendar\Message\PullFromGoogleCommand;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Elasticsearch\Middleware\ElasticsearchIndexMiddleware;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Middleware\MiddlewareInterface;
use Symfony\Component\Messenger\Middleware\StackInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Messenger\Stamp\ReceivedStamp;
use Symfony\Component\Uid\Ulid;

class ElasticsearchIndexMiddlewareTest extends TestCase
{
    /** @var object[] */
    private array $dispatched = [];
    private MessageBusInterface $bus;
    private IndexMetadataReader $metadataReader;
    private User $user;

    protected function setUp(): void
    {
        $this->dispatched = [];

        $this->bus = $this->createMock(MessageBusInterface::class);
        $this->bus->method('dispatch')->willReturnCallback(function (object $message) {
            $this->dispatched[] = $message;
            return new Envelope($message);
        });

        $this->metadataReader = new IndexMetadataReader();

        $this->user = new User();
        $this->user->setEmail('test@example.com');
        $this->user->setGoogleId('google-test-id');
        $this->user->setName('Test User');
    }

    private function createPassthroughStack(?object $result = null): StackInterface
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

    private function createMiddleware(): ElasticsearchIndexMiddleware
    {
        return new ElasticsearchIndexMiddleware($this->bus, $this->metadataReader);
    }

    private function received(object $command): Envelope
    {
        return new Envelope($command, [new ReceivedStamp('sync')]);
    }

    public function testCreateEventDispatchesIndexCommand(): void
    {
        $agenda = new Agenda();
        $agenda->setName('Work');
        $agenda->setUser($this->user);

        $event = new Event();
        $event->setSummary('Meeting');
        $event->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));
        $event->setAgenda($agenda);

        $envelope = $this->received(new CreateEventCommand('Meeting', $event->getStartAt(), $event->getEndAt()));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($event));

        self::assertCount(1, $this->dispatched);
        self::assertInstanceOf(IndexDocumentCommand::class, $this->dispatched[0]);
        self::assertSame(Event::class, $this->dispatched[0]->entityClass);
        self::assertSame((string) $event->getId(), $this->dispatched[0]->entityId);
    }

    public function testUpdateEventDispatchesIndexCommand(): void
    {
        $agenda = new Agenda();
        $agenda->setName('Work');
        $agenda->setUser($this->user);

        $event = new Event();
        $event->setSummary('Updated Meeting');
        $event->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));
        $event->setAgenda($agenda);

        $envelope = $this->received(new UpdateEventCommand(eventId: (string) $event->getId()));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($event));

        self::assertCount(1, $this->dispatched);
        self::assertInstanceOf(IndexDocumentCommand::class, $this->dispatched[0]);
    }

    public function testDeleteEventDispatchesDeleteCommand(): void
    {
        $eventId = (string) new Ulid();

        $envelope = $this->received(new DeleteEventCommand(eventId: $eventId));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack());

        self::assertCount(1, $this->dispatched);
        self::assertInstanceOf(DeleteDocumentCommand::class, $this->dispatched[0]);
        self::assertSame('events', $this->dispatched[0]->indexName);
        self::assertSame($eventId, $this->dispatched[0]->documentId);
    }

    public function testNonCrudCommandDoesNotDispatch(): void
    {
        $envelope = $this->received(new PullFromGoogleCommand(agendaId: (string) new Ulid()));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack());

        self::assertCount(0, $this->dispatched);
    }

    public function testWithoutReceivedStampDoesNotDispatch(): void
    {
        $agenda = new Agenda();
        $agenda->setName('Work');
        $agenda->setUser($this->user);

        $event = new Event();
        $event->setSummary('Meeting');
        $event->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));
        $event->setAgenda($agenda);

        $envelope = new Envelope(new CreateEventCommand('Meeting', $event->getStartAt(), $event->getEndAt()));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack($event));

        self::assertCount(0, $this->dispatched);
    }

    public function testNonIndexableResultDoesNotDispatch(): void
    {
        $envelope = $this->received(new CreateEventCommand('Meeting', new \DateTimeImmutable(), new \DateTimeImmutable()));
        $this->createMiddleware()->handle($envelope, $this->createPassthroughStack(new \stdClass()));

        self::assertCount(0, $this->dispatched);
    }
}
