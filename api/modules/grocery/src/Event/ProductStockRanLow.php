<?php

declare(strict_types=1);

namespace Maggie\Grocery\Event;

use Maggie\Grocery\Enum\ProductStockState;

/** A product left « en stock » for low or out. Not raised again while it stays short. */
final readonly class ProductStockRanLow
{
    public function __construct(
        public string $productId,
        public ProductStockState $state,
    ) {
    }
}
