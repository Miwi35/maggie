<?php

declare(strict_types=1);

namespace Maggie\Grocery\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\RecurringGroceryItem;

class DeleteProduct
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Product $product): void
    {
        // The items outlive the product: the reference is detached and the product name
        // becomes their label, so the list reads the same.
        $items = $this->em->getRepository(GroceryItem::class)->findBy(['product' => $product]);
        $recurringItems = $this->em->getRepository(RecurringGroceryItem::class)->findBy(['product' => $product]);

        foreach ($items as $item) {
            $item->setCustomLabel($item->getLabel());
            $item->setProduct(null);
        }
        foreach ($recurringItems as $recurringItem) {
            $recurringItem->setCustomLabel($recurringItem->getLabel());
            $recurringItem->setProduct(null);
        }

        $this->em->remove($product);
        $this->em->flush();
    }
}
