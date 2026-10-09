<?php

declare(strict_types=1);

namespace Maggie\Grocery\EventSubscriber;

use Maggie\Grocery\Event\ProductOutOfStockEvent;
use Maggie\Grocery\Message\RestockProductCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/** Turns the fact into a command; the rule lives in the use case. */
#[AsMessageHandler(bus: 'event.bus')]
final class RestockOnProductOutOfStock
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(ProductOutOfStockEvent $event): void
    {
        $this->commandBus->dispatch(new RestockProductCommand($event->productId));
    }
}
