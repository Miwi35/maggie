<?php

declare(strict_types=1);

namespace Maggie\Grocery\UseCase;

use Maggie\Grocery\Entity\Store;
use Doctrine\ORM\EntityManagerInterface;

class UpdateStore
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Store $store): Store
    {
        $this->em->flush();

        return $store;
    }
}
