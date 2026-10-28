<?php

declare(strict_types=1);

namespace Maggie\Calendar\EventSubscriber;

use Maggie\Calendar\Event\TripRemoved;
use Maggie\Notification\Message\ExpireNotificationsCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/** « Pars maintenant » for a trip that is gone is not worth saying any more (MAG-679). */
#[AsMessageHandler(bus: 'event.bus')]
final class ExpireNotificationsOnTripRemoved
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(TripRemoved $event): void
    {
        $this->commandBus->dispatch(new ExpireNotificationsCommand($event->iri()));
    }
}
