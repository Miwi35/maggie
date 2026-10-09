<?php

declare(strict_types=1);

namespace Maggie\Grocery\Specification;

use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\ProductStockState;

/** The product runs out: it was in stock (or is being created) and is now low or out. */
final readonly class IsProductOutOfStock
{
    /** @param array<string, array{0: mixed, 1: mixed}> $changeSet what Doctrine is about to write: field => [before, after] */
    public function __construct(
        private array $changeSet,
    ) {
    }

    public function isSatisfiedBy(Product $product): bool
    {
        if (!isset($this->changeSet['stockState'])) {
            return false;
        }

        // Doctrine hands the previous value over as the column's string; a creation has none.
        $before = $this->changeSet['stockState'][0];
        $before = $before instanceof ProductStockState ? $before : ProductStockState::tryFrom((string) $before);
        $wasInStock = null === $before || ProductStockState::InStock === $before;

        return $wasInStock && ProductStockState::InStock !== $product->getStockState();
    }
}
