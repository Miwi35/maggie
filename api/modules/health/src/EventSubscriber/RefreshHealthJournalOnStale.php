<?php

declare(strict_types=1);

namespace Maggie\Health\EventSubscriber;

use Maggie\Health\Event\HealthJournalStale;
use Maggie\Health\Message\RefreshHealthDayCommand;
use Maggie\Health\Repository\HealthDayRepository;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\DispatchAfterCurrentBusStamp;

/**
 * One refresh per journal day concerned, after the message being handled
 * like {@see RefreshHealthDayOnSourceChanged}. A day not in the journal has
 * no figure to correct.
 */
#[AsMessageHandler(bus: 'event.bus')]
final class RefreshHealthJournalOnStale
{
    public function __construct(
        private readonly HealthDayRepository $days,
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(HealthJournalStale $event): void
    {
        foreach ($this->days->datesOf($event->userId) as $date) {
            if (null === $event->from || $date >= $event->from) {
                $this->commandBus->dispatch(new RefreshHealthDayCommand($event->userId, $date), [new DispatchAfterCurrentBusStamp()]);
            }
        }
    }
}
