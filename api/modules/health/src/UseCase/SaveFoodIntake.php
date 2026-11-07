<?php

declare(strict_types=1);

namespace Maggie\Health\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\FoodIntake;

class SaveFoodIntake
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(FoodIntake $intake): FoodIntake
    {
        $this->em->persist($intake);
        $this->em->flush();

        return $intake;
    }
}
