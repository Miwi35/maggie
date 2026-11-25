<?php

declare(strict_types=1);

namespace Maggie\Calendar\EventSubscriber;

use Maggie\Calendar\Event\TaskReopened;
use Maggie\Calendar\Message\RewindTaskRecurrenceCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * An occurrence reopened gives the series back (MAG-1033), whichever door
 * unticked it. Turns the fact into a command; the rule lives in its handler.
 */
#[AsMessageHandler(bus: 'event.bus')]
final class RewindRecurrenceOnTaskReopened
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(TaskReopened $event): void
    {
        $this->commandBus->dispatch(new RewindTaskRecurrenceCommand($event->userId, $event->taskId));
    }
}
