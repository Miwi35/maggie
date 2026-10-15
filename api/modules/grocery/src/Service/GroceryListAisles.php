<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Maggie\Core\Entity\User;
use Maggie\Core\Projection\WorkCollector;
use Maggie\Grocery\Repository\GroceryListRepository;

/**
 * Every line of the grocery list carries its aisle (name, position): when an
 * aisle or a product's aisle changes, no line row does, so the list is
 * announced by hand for the open screens to redraw it in the new order.
 */
class GroceryListAisles
{
    public function __construct(
        private readonly GroceryListRepository $groceryListRepository,
        private readonly WorkCollector $projection,
    ) {
    }

    public function announce(User $user): void
    {
        $list = $this->groceryListRepository->findOneBy(['user' => $user]);
        if (null !== $list) {
            $this->projection->touched($list, ['items']);
        }
    }
}
