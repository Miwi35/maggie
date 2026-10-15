<?php

declare(strict_types=1);

namespace Maggie\Grocery\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Entity\ProductAisle;
use Maggie\Grocery\Service\AisleOrder;
use Maggie\Grocery\Service\GroceryListAisles;

class DeleteAisle
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly AisleOrder $aisleOrder,
        private readonly GroceryListAisles $groceryListAisles,
    ) {
    }

    /**
     * Its products become « Sans rayon » in its store, nothing else goes. The
     * foreign key would drop their rows by itself, but then no product would
     * be announced (the projection reads Doctrine's change set). Flushes.
     */
    public function execute(Aisle $aisle): void
    {
        $this->detachProducts($aisle);
        $this->aisleOrder->closeGaps($aisle->getStore(), $aisle);
        $this->em->remove($aisle);
        $this->em->flush();

        $this->groceryListAisles->announce($aisle->getUser());
    }

    /** Also used when the whole store goes. */
    public function detachProducts(Aisle $aisle): void
    {
        foreach ($this->em->getRepository(ProductAisle::class)->findBy(['aisle' => $aisle]) as $assignment) {
            $assignment->getProduct()->detachAisleIn($assignment->getStore());
        }
    }
}
