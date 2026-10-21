<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Placement;

class DeletePlacement
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Placement $placement): void
    {
        $this->em->remove($placement);
        $this->em->flush();
    }
}
