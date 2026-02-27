<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Maggie\Cookbook\Entity\Store;
use Doctrine\ORM\EntityManagerInterface;

class CreateStore
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Store $store): Store
    {
        $this->em->persist($store);
        $this->em->flush();

        return $store;
    }
}
