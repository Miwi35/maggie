<?php

declare(strict_types=1);

namespace Maggie\Grocery\Event;

use Maggie\Grocery\Entity\Product;

/** A product (an ingredient too) left « en stock » for low or out. Not raised again while it stays short. */
final readonly class ProductOutOfStockEvent
{
    public function __construct(
        public Product $product,
    ) {
    }
}
