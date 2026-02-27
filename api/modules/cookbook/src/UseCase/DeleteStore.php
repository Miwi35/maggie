<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Maggie\Cookbook\Entity\Store;
use Doctrine\ORM\EntityManagerInterface;

class DeleteStore
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Store $store): void
    {
        $this->em->remove($store);
        $this->em->flush();
    }
}
