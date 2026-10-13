<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Service\MealGrocerySync;
use Maggie\Grocery\Entity\GroceryList;

/** Takes a past meal's unbought share off the list. */
class ReleaseMealGroceries
{
    public function __construct(
        private readonly MealGrocerySync $mealGrocerySync,
        private readonly EntityManagerInterface $em,
    ) {
    }

    /** @return GroceryList|null the list that lost shopping, null when the meal held nothing unbought */
    public function execute(Meal $meal): ?GroceryList
    {
        $list = $this->mealGrocerySync->release($meal);

        if (null !== $list) {
            $this->em->flush();
        }

        return $list;
    }
}
