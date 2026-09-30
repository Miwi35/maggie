<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Meal;

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
