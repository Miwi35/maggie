<?php

declare(strict_types=1);

namespace Maggie\Health\EventSubscriber;

use Maggie\Health\Event\SportEventChanged;
use Maggie\Health\Message\ApplySportEventChangeCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/** Turns the fact into a command; the rule lives in the use case. */
#[AsMessageHandler(bus: 'event.bus')]
final class ApplyOnSportEventChanged
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(SportEventChanged $event): void
    {
        $this->commandBus->dispatch(new ApplySportEventChangeCommand($event->userId, $event->eventId, $event->removed, $event->workoutId));
    }
}
