<?php

declare(strict_types=1);

namespace Maggie\Memory\UseCase;

use Maggie\Memory\Entity\Memory;
use Doctrine\ORM\EntityManagerInterface;

class DeleteMemory
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Memory $memory): void
    {
        $this->em->remove($memory);
        $this->em->flush();
    }
}
