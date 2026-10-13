<?php

declare(strict_types=1);

namespace Maggie\Calendar\EventSubscriber;

use Maggie\Calendar\Event\ConnectedAgendaRenamed;
use Maggie\Calendar\Message\PushAgendaNameToGoogleCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/** Turns the fact into a command; the rule lives in the use case. */
#[AsMessageHandler(bus: 'event.bus')]
final class PushAgendaNameOnConnectedAgendaRenamed
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(ConnectedAgendaRenamed $event): void
    {
        $this->commandBus->dispatch(new PushAgendaNameToGoogleCommand($event->agendaId, $event->previousName));
    }
}
