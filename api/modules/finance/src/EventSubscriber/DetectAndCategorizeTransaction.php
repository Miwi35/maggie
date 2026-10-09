<?php

declare(strict_types=1);

namespace Maggie\Finance\EventSubscriber;

use Maggie\Finance\Event\TransactionChanged;
use Maggie\Finance\Event\TransactionRecorded;
use Maggie\Finance\Message\CategorizeTransactionCommand;
use Maggie\Finance\Message\DetectInternalTransferCommand;
use Maggie\Finance\Message\DetectRejectionCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Turns the fact into the three commands, in the order they depend on each
 * other: a line is a transfer, or a rejection, or else it is filed under a
 * category. The rule of each lives in its use case.
 */
final class DetectAndCategorizeTransaction
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    #[AsMessageHandler(bus: 'event.bus')]
    public function onRecorded(TransactionRecorded $event): void
    {
        $this->dispatchFor($event->transactionId);
    }

    #[AsMessageHandler(bus: 'event.bus')]
    public function onChanged(TransactionChanged $event): void
    {
        $this->dispatchFor($event->transactionId);
    }

    private function dispatchFor(string $transactionId): void
    {
        $this->commandBus->dispatch(new DetectInternalTransferCommand($transactionId));
        $this->commandBus->dispatch(new DetectRejectionCommand($transactionId));
        $this->commandBus->dispatch(new CategorizeTransactionCommand($transactionId));
    }
}
