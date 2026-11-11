<?php

declare(strict_types=1);

namespace Maggie\Health\EventSubscriber;

use Maggie\Health\Event\PlannedWorkoutChanged;
use Maggie\Health\Event\PlannedWorkoutRemoved;
use Maggie\Health\Message\FilePlannedWorkoutCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/** Turns the facts into a command; the rule lives in the use case. */
final class FilePlannedWorkoutOnChange
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    #[AsMessageHandler(bus: 'event.bus')]
    public function onChanged(PlannedWorkoutChanged $event): void
    {
        $this->commandBus->dispatch(new FilePlannedWorkoutCommand($event->userId, $event->workoutId));
    }

    #[AsMessageHandler(bus: 'event.bus')]
    public function onRemoved(PlannedWorkoutRemoved $event): void
    {
        $this->commandBus->dispatch(new FilePlannedWorkoutCommand($event->userId, $event->workoutId, $event->eventId));
    }
}
