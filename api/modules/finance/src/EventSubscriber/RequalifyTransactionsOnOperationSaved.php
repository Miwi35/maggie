<?php

declare(strict_types=1);

namespace Maggie\Finance\EventSubscriber;

use Maggie\Finance\Event\OperationQualificationSaved;
use Maggie\Finance\Message\RequalifyTransactionsCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/** Turns the fact into a command; the rule lives in the use case. */
#[AsMessageHandler(bus: 'event.bus')]
final class RequalifyTransactionsOnOperationSaved
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(OperationQualificationSaved $event): void
    {
        $this->commandBus->dispatch(new RequalifyTransactionsCommand($event->operationClass, $event->operationId));
    }
}
