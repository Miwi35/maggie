<?php

declare(strict_types=1);

namespace Maggie\Finance\EventSubscriber;

use Maggie\Finance\Event\OperationSaved;
use Maggie\Finance\Message\ApplyOperationCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/** Turns the fact into a command when the operation was saved to be applied; the rule lives in the use case. */
#[AsMessageHandler(bus: 'event.bus')]
final class ApplyOperationOnOperationSaved
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(OperationSaved $event): void
    {
        if (!$event->applyToExisting) {
            return;
        }

        $this->commandBus->dispatch(new ApplyOperationCommand($event->operationId));
    }
}
