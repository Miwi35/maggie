<?php

namespace App\Tests\Calendar\Middleware;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Entity\TaskCriticality;
use Maggie\Calendar\Entity\TaskPriority;
use Maggie\Calendar\Message\CreateAgendaCommand;
use Maggie\Calendar\Message\CreateEventCommand;
use Maggie\Calendar\Message\CreateTaskCommand;
use Maggie\Calendar\Message\DeleteAgendaCommand;
use Maggie\Calendar\Message\DeleteEventCommand;
use Maggie\Calendar\Message\DeleteTaskCommand;
use Maggie\Calendar\Message\UpdateAgendaCommand;
use Maggie\Calendar\Message\UpdateEventCommand;
use Maggie\Calendar\Message\UpdateTaskCommand;
use Maggie\Calendar\Middleware\MercurePublishMiddleware;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Middleware\MiddlewareInterface;
use Symfony\Component\Messenger\Middleware\StackInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Uid\Ulid;

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
            return 'urn:uuid:' . new Ulid();
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
        $eventId = (string) new Ulid();

        $middleware = new MercurePublishMiddleware($this->hub);
        $envelope = new Envelope(new DeleteEventCommand(eventId: $eventId));

        $middleware->handle($envelope, $this->createPassthroughStack());

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('/api/events/' . $eventId, $data['@id']);
        self::assertTrue($data['deleted']);
    }

    public function testCreateAgendaPublishesToMercure(): void
    {
        $agenda = new Agenda();
        $agenda->setName('Work');
        $agenda->setColor('#ff0000');

        $middleware = new MercurePublishMiddleware($this->hub);
        $envelope = new Envelope(new CreateAgendaCommand(name: 'Work', color: '#ff0000'));

        $middleware->handle($envelope, $this->createPassthroughStack($agenda));

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Work', $data['name']);
        self::assertSame('#ff0000', $data['color']);
        self::assertStringContainsString('/api/agendas/', $data['@id']);
    }

    public function testUpdateAgendaPublishesToMercure(): void
    {
        $agenda = new Agenda();
        $agenda->setName('Personal');

        $middleware = new MercurePublishMiddleware($this->hub);
        $envelope = new Envelope(new UpdateAgendaCommand(agendaId: (string) $agenda->getId()));

        $middleware->handle($envelope, $this->createPassthroughStack($agenda));

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Personal', $data['name']);
    }

    public function testDeleteAgendaPublishesToMercure(): void
    {
        $agendaId = (string) new Ulid();

        $middleware = new MercurePublishMiddleware($this->hub);
        $envelope = new Envelope(new DeleteAgendaCommand(agendaId: $agendaId));

        $middleware->handle($envelope, $this->createPassthroughStack());

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('/api/agendas/' . $agendaId, $data['@id']);
        self::assertTrue($data['deleted']);
    }

    public function testCreateTaskPublishesToMercure(): void
    {
        $task = new Task();
        $task->setName('Buy groceries');
        $task->setPriority(TaskPriority::High);
        $task->setCriticality(TaskCriticality::Medium);
        $task->setDueDate(new \DateTimeImmutable('2026-03-25T18:00:00+01:00'));

        $middleware = new MercurePublishMiddleware($this->hub);
        $envelope = new Envelope(new CreateTaskCommand(name: 'Buy groceries'));

        $middleware->handle($envelope, $this->createPassthroughStack($task));

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Buy groceries', $data['name']);
        self::assertSame('high', $data['priority']);
        self::assertSame('medium', $data['criticality']);
        self::assertFalse($data['isDone']);
        self::assertStringContainsString('/api/tasks/', $data['@id']);
    }

    public function testUpdateTaskPublishesToMercure(): void
    {
        $task = new Task();
        $task->setName('Updated task');
        $task->setPriority(TaskPriority::Low);
        $task->setCriticality(TaskCriticality::Critical);

        $middleware = new MercurePublishMiddleware($this->hub);
        $envelope = new Envelope(new UpdateTaskCommand(taskId: (string) $task->getId()));

        $middleware->handle($envelope, $this->createPassthroughStack($task));

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Updated task', $data['name']);
        self::assertSame('low', $data['priority']);
        self::assertSame('critical', $data['criticality']);
    }

    public function testDeleteTaskPublishesToMercure(): void
    {
        $taskId = (string) new Ulid();

        $middleware = new MercurePublishMiddleware($this->hub);
        $envelope = new Envelope(new DeleteTaskCommand(taskId: $taskId));

        $middleware->handle($envelope, $this->createPassthroughStack());

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('/api/tasks/' . $taskId, $data['@id']);
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
