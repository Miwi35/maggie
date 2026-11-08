<?php

declare(strict_types=1);

namespace Maggie\Health\EventSubscriber;

use Maggie\Health\Event\HealthDaySourceChanged;
use Maggie\Health\Message\RefreshHealthDayCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\DispatchAfterCurrentBusStamp;

/**
 * Turns the fact into a command; the figures are read by the use case. The
 * command waits for the message being handled to finish: the event is raised
 * from a postFlush, and refreshing there would flush inside the flush. Raised
 * outside any message (fixtures, a bare flush), it runs at once.
 */
#[AsMessageHandler(bus: 'event.bus')]
final class RefreshHealthDayOnSourceChanged
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(HealthDaySourceChanged $event): void
    {
        $this->commandBus->dispatch(new RefreshHealthDayCommand($event->userId, $event->date), [new DispatchAfterCurrentBusStamp()]);
    }
}
