<?php

declare(strict_types=1);

namespace Maggie\Grocery\EventSubscriber;

use Maggie\Grocery\Event\ProductOutOfStockEvent;
use Maggie\Grocery\UseCase\RestockProduct;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler(bus: 'event.bus')]
final class RestockOnProductOutOfStock
{
    public function __construct(
        private readonly RestockProduct $restockProduct,
    ) {
    }

    public function __invoke(ProductOutOfStockEvent $event): void
    {
        $this->restockProduct->execute($event->product);
    }
}
