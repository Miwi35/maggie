<?php

declare(strict_types=1);

namespace Maggie\Calendar\EventSubscriber;

use Maggie\Calendar\Event\TaskCompleted;
use Maggie\Calendar\Message\GrantTaskRewardCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * A task done earns its reward (MAG-508), whichever door ticked it. Reopening
 * it takes nothing back: {@see \Maggie\Calendar\Event\TaskReopened} has no
 * effect here, a star is never lost. Turns the fact into a command; the rule
 * lives in its handler.
 */
#[AsMessageHandler(bus: 'event.bus')]
final class GrantRewardOnTaskCompleted
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(TaskCompleted $event): void
    {
        $this->commandBus->dispatch(new GrantTaskRewardCommand($event->userId, $event->taskId));
    }
}
