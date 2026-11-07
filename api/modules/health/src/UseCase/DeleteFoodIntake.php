<?php

declare(strict_types=1);

namespace Maggie\Health\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\FoodIntake;

class DeleteFoodIntake
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(FoodIntake $intake): void
    {
        $this->em->remove($intake);
        $this->em->flush();
    }
}
