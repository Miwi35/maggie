<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Placement;

class SavePlacement
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Placement $placement): Placement
    {
        $this->em->persist($placement);
        $this->em->flush();

        return $placement;
    }
}
