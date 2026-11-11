<?php

declare(strict_types=1);

namespace Maggie\Health\EventSubscriber;

use Maggie\Health\Event\PlannedWorkoutKept;
use Maggie\Health\Message\KeepPlannedWorkoutCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler(bus: 'event.bus')]
final class KeepPlannedWorkoutOnKept
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(PlannedWorkoutKept $event): void
    {
        $this->commandBus->dispatch(new KeepPlannedWorkoutCommand($event->doneWorkoutId, $event->plannedWorkoutId));
    }
}
