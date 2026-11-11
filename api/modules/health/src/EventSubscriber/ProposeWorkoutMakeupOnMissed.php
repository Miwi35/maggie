<?php

declare(strict_types=1);

namespace Maggie\Health\EventSubscriber;

use Maggie\Health\Event\PlannedWorkoutMissed;
use Maggie\Health\Message\ProposeWorkoutMakeupCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler(bus: 'event.bus')]
final class ProposeWorkoutMakeupOnMissed
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(PlannedWorkoutMissed $event): void
    {
        $this->commandBus->dispatch(new ProposeWorkoutMakeupCommand($event->plannedWorkoutId));
    }
}
