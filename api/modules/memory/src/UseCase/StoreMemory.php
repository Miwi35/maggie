<?php

declare(strict_types=1);

namespace Maggie\Memory\UseCase;

use Maggie\Memory\Entity\Memory;
use Doctrine\ORM\EntityManagerInterface;

class StoreMemory
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Memory $memory): Memory
    {
        $this->em->persist($memory);
        $this->em->flush();

        return $memory;
    }
}
