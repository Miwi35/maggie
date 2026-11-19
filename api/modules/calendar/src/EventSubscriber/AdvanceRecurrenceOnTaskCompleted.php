<?php

declare(strict_types=1);

namespace Maggie\Calendar\EventSubscriber;

use Maggie\Calendar\Event\TaskCompleted;
use Maggie\Calendar\Message\AdvanceTaskRecurrenceCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * A recurring task done hands its rule to the next occurrence (MAG-870),
 * whichever door ticked it. Turns the fact into a command; the rule lives in
 * its handler.
 */
#[AsMessageHandler(bus: 'event.bus')]
final class AdvanceRecurrenceOnTaskCompleted
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(TaskCompleted $event): void
    {
        $this->commandBus->dispatch(new AdvanceTaskRecurrenceCommand($event->userId, $event->taskId));
    }
}
