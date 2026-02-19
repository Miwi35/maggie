<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Maggie\Cookbook\Entity\Meal;
use Doctrine\ORM\EntityManagerInterface;

class DeleteMeal
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Meal $meal): void
    {
        $this->em->remove($meal);
        $this->em->flush();
    }
}
