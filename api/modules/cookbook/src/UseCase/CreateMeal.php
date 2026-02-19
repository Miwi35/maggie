<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Maggie\Cookbook\Entity\Meal;
use Doctrine\ORM\EntityManagerInterface;

class CreateMeal
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Meal $meal): Meal
    {
        $this->em->persist($meal);
        $this->em->flush();

        return $meal;
    }
}
