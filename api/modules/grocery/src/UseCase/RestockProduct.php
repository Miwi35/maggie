<?php

declare(strict_types=1);

namespace Maggie\Grocery\UseCase;

use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Service\GroceryListBroadcaster;
use Maggie\Grocery\Service\GroceryListItems;

/** Puts a product's restock quantity back on its owner's list, when it restocks by itself. */
class RestockProduct
{
    public function __construct(
        private readonly GroceryListItems $groceryListItems,
        private readonly GroceryListBroadcaster $listBroadcaster,
    ) {
    }

    /** @return GroceryList|null the list that gained the line, null when the product does not restock by itself */
    public function execute(Product $product): ?GroceryList
    {
        $quantity = $product->getRestockQuantity();
        if (!$product->isAutoRestock() || null === $quantity || $quantity <= 0) {
            return null;
        }

        $list = $this->groceryListItems->addProduct(
            $product->getUser(),
            $product,
            $quantity,
            $product->getPackagingUnit(),
            GroceryItemSource::Restock,
        );

        // No command returns the list here: the open screens and the index get it from this call.
        $this->listBroadcaster->broadcast($list);

        return $list;
    }
}
