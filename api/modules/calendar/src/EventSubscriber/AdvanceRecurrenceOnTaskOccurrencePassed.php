<?php

declare(strict_types=1);

namespace Maggie\Calendar\EventSubscriber;

use Maggie\Calendar\Event\TaskOccurrencePassed;
use Maggie\Calendar\Message\AdvanceTaskRecurrenceCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * A recurring task whose day has passed undone hands its rule to the next
 * occurrence (MAG-870); the missed one stays open. Turns the fact into a
 * command; the rule lives in its handler.
 */
#[AsMessageHandler(bus: 'event.bus')]
final class AdvanceRecurrenceOnTaskOccurrencePassed
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(TaskOccurrencePassed $event): void
    {
        $this->commandBus->dispatch(new AdvanceTaskRecurrenceCommand($event->userId, $event->taskId));
    }
}
