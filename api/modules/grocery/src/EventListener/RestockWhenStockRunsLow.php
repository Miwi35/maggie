<?php

declare(strict_types=1);

namespace Maggie\Grocery\EventListener;

use Maggie\Grocery\Event\ProductStockRanLow;
use Maggie\Grocery\Message\RestockProductCommand;
use Maggie\Grocery\Repository\ProductRepository;
use Symfony\Component\EventDispatcher\Attribute\AsEventListener;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsEventListener]
final class RestockWhenStockRunsLow
{
    public function __construct(
        private readonly ProductRepository $productRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(ProductStockRanLow $event): void
    {
        $product = $this->productRepository->find($event->productId);
        if (null === $product || !$product->isAutoRestock() || ($product->getRestockQuantity() ?? 0) <= 0) {
            return;
        }

        $this->bus->dispatch(new RestockProductCommand($event->productId));
    }
}
