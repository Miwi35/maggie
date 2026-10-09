<?php

declare(strict_types=1);

namespace Maggie\Grocery\Event;

/** A product (an ingredient too) left « en stock » for low or out. Not raised again while it stays short. */
final readonly class ProductOutOfStockEvent
{
    public function __construct(
        public string $productId,
    ) {
    }
}
