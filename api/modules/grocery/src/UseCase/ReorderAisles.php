<?php

declare(strict_types=1);

namespace Maggie\Grocery\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Exception\InvalidAisleException;
use Maggie\Grocery\Service\AisleOrder;
use Maggie\Grocery\Service\GroceryListAisles;

class ReorderAisles
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly AisleOrder $aisleOrder,
        private readonly GroceryListAisles $groceryListAisles,
    ) {
    }

    /**
     * @param list<string> $aisleIds every aisle of the store, first one first
     *
     * @return Aisle[]
     *
     * @throws InvalidAisleException
     */
    public function execute(Store $store, array $aisleIds): array
    {
        $aisles = $this->aisleOrder->reorder($store, $aisleIds);

        $this->em->flush();
        $this->groceryListAisles->announce($store->getUser());

        return $aisles;
    }
}
