<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\State;

use ApiPlatform\Metadata\Post;
use Doctrine\DBAL\Driver\Exception as DriverException;
use Doctrine\DBAL\Exception\UniqueConstraintViolationException;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Message\CreateTaskCommand;
use Maggie\Calendar\State\CreateTaskProcessor;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\TestCase;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\RequestStack;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

final class CreateTaskProcessorTest extends TestCase
{
    public function testReplaysOnceWhenTwoFirstTasksRaceOnTheTasksAgenda(): void
    {
        $command = null;
        $created = new Task();
        $unique = new UniqueConstraintViolationException($this->createStub(DriverException::class), null);
        $failure = new HandlerFailedException(new Envelope(new \stdClass()), [$unique]);

        $bus = $this->createMock(MessageBusInterface::class);
        $calls = 0;
        $bus->expects($this->exactly(2))->method('dispatch')->willReturnCallback(
            function (CreateTaskCommand $c) use (&$calls, &$command, $failure, $created): Envelope {
                $command ??= $c;
                $this->assertSame($command, $c);
                if (1 === ++$calls) {
                    throw $failure;
                }

                return (new Envelope($c))->with(new HandledStamp($created, 'handler'));
            },
        );
        $registry = $this->createMock(ManagerRegistry::class);
        $registry->expects($this->once())->method('resetManager');
        $security = $this->createStub(Security::class);
        $security->method('getUser')->willReturn(new User());

        $processor = new CreateTaskProcessor($bus, new RequestStack(), $security, $registry);
        $task = new Task();
        $task->setTitle('Buy bread');

        $this->assertSame($created, $processor->process($task, new Post()));
    }
}
