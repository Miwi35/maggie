<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Service\MealGrocerySync;
use Maggie\Grocery\Entity\GroceryList;

/** Makes the list say what a stored meal needs now. */
class SyncMealGroceries
{
    public function __construct(
        private readonly MealGrocerySync $mealGrocerySync,
    ) {
    }

    /** @return GroceryList|null the list it touched, null when the meal changes no shopping */
    public function execute(Meal $meal): ?GroceryList
    {
        return $this->mealGrocerySync->sync($meal);
    }
}
