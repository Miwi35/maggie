<?php

declare(strict_types=1);

namespace Maggie\Grocery\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Exception\InvalidAisleException;
use Maggie\Grocery\Service\AisleOrder;
use Maggie\Grocery\Service\GroceryListAisles;

class UpdateAisle
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly AisleOrder $aisleOrder,
        private readonly GroceryListAisles $groceryListAisles,
    ) {
    }

    /**
     * Renames the aisle and/or moves it to a position (1 = first), the others
     * keeping their order around it. Flushes; the grocery list follows.
     *
     * @throws InvalidAisleException
     */
    public function execute(Aisle $aisle, ?string $name, ?int $position): Aisle
    {
        if (null !== $name) {
            $aisle->setName($this->aisleOrder->checkedName($aisle->getStore(), $name, $aisle));
        }
        if (null !== $position) {
            $this->aisleOrder->place($aisle, $position);
        }

        $this->em->flush();
        $this->groceryListAisles->announce($aisle->getUser(), $aisle->getStore());

        return $aisle;
    }
}
