<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;

final readonly class EndErrandResult
{
    /** @param list<Product> $restockedProducts */
    public function __construct(
        public GroceryList $list,
        public array $restockedProducts,
    ) {
    }
}
