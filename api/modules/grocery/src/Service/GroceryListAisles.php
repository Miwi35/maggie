<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Projection\WorkCollector;
use Maggie\Grocery\Entity\ProductAisle;
use Maggie\Grocery\Entity\Store;
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
        private readonly EntityManagerInterface $em,
    ) {
    }

    /**
     * The products index the position of their aisle (`aisles`, MAG-604) to be
     * sorted by it: an aisle moved or renamed in a store leaves them stale.
     * Without `$store`, only the list is announced.
     */
    public function announce(User $user, ?Store $store = null): void
    {
        if (null !== $store) {
            foreach ($this->em->getRepository(ProductAisle::class)->findBy(['store' => $store]) as $assignment) {
                $this->projection->touched($assignment->getProduct(), ['aisleAssignments']);
            }
        }

        $list = $this->groceryListRepository->findOneBy(['user' => $user]);
        if (null !== $list) {
            $this->projection->touched($list, ['items']);
        }
    }
}
