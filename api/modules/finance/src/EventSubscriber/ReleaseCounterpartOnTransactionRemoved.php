<?php

declare(strict_types=1);

namespace Maggie\Finance\EventSubscriber;

use Maggie\Finance\Event\TransactionRemoved;
use Maggie\Finance\Message\ReleaseCounterpartCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/** Turns the fact into a command; the rule lives in the handler's use case. */
#[AsMessageHandler(bus: 'event.bus')]
final class ReleaseCounterpartOnTransactionRemoved
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(TransactionRemoved $event): void
    {
        $this->commandBus->dispatch(new ReleaseCounterpartCommand($event->counterpartId, $event->transactionId));
    }
}
