<?php

declare(strict_types=1);

namespace Maggie\Grocery\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Exception\InvalidAisleException;
use Maggie\Grocery\Service\AisleOrder;

class CreateAisle
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly AisleOrder $aisleOrder,
    ) {
    }

    /**
     * A new aisle of the store, last on the way through it unless a position
     * (1 = first) says where. Flushes.
     *
     * @throws InvalidAisleException
     */
    public function execute(User $user, Store $store, string $name, ?int $position = null): Aisle
    {
        if ((string) $store->getUser()->getId() !== (string) $user->getId()) {
            throw new InvalidAisleException(sprintf('Store not found: %s', $store->getId()));
        }

        $aisle = new Aisle();
        $aisle->setUser($user);
        $aisle->setStore($store);
        $aisle->setName($this->aisleOrder->checkedName($store, $name));
        $this->em->persist($aisle);

        $this->aisleOrder->place($aisle, $position);
        $this->em->flush();

        return $aisle;
    }
}
