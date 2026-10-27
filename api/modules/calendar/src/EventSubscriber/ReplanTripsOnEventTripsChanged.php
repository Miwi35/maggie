<?php

declare(strict_types=1);

namespace Maggie\Calendar\EventSubscriber;

use Maggie\Calendar\Event\EventTripsChangedEvent;
use Maggie\Calendar\Message\ReplanTripsCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/** Turns the fact into a command; the rule lives in the command's handler. */
#[AsMessageHandler(bus: 'event.bus')]
final class ReplanTripsOnEventTripsChanged
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(EventTripsChangedEvent $event): void
    {
        $reason = match ($event->change) {
            EventTripsChangedEvent::CREATED => ReplanTripsCommand::EVENT_CREATED,
            EventTripsChangedEvent::DELETED => ReplanTripsCommand::EVENT_DELETED,
            default => ReplanTripsCommand::EVENT_UPDATED,
        };

        $this->commandBus->dispatch(new ReplanTripsCommand($event->userId, $reason));
    }
}
