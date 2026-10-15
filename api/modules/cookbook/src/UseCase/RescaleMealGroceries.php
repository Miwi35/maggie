<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Service\MealGroceryChoice;
use Maggie\Cookbook\Service\MealGrocerySync;
use Maggie\Cookbook\Service\MealGuests;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Service\Needs;

/**
 * Makes the list follow a meal's new number of guests (MAG-482).
 *
 * Each line the meal holds moves by the ratio of what its recipes need at the
 * new number to what they needed at the old: 400 g for 4 that became 6 is
 * 600 g. A line the recipes do not ask for in that unit — packagings the owner
 * forced, a recipe swapped since — keeps what the owner chose.
 */
class RescaleMealGroceries
{
    public function __construct(
        private readonly MealGroceryChoice $choice,
        private readonly MealGrocerySync $mealGrocerySync,
        private readonly MealGuests $mealGuests,
    ) {
    }

    /** @return GroceryList|null the list it touched, null when the meal changes no shopping */
    public function execute(Meal $meal, int $previousGuests): ?GroceryList
    {
        $guests = $this->mealGuests->of($meal);
        if ($guests === $previousGuests) {
            return null;
        }

        $before = $this->choice->needsByProductAndUnit($meal, $previousGuests);
        $after = $this->choice->needsByProductAndUnit($meal, $guests);

        $ratios = [];
        foreach ($after as $key => $quantity) {
            if (($before[$key] ?? 0.0) > Needs::EPSILON) {
                $ratios[$key] = $quantity / $before[$key];
            }
        }

        return $this->mealGrocerySync->rescale($meal, $ratios);
    }
}
