<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Enum\MealSlot;

// The title of a meal is the API's unless the owner typed one (MAG-378): by
// default the names of its recipes, in the order they were chosen. The slot is not in it — the week view
// already shows which slot a meal is in. A meal with no recipe is named after
// its slot, since an event needs a title.
final class MealTitle
{
    public static function of(Meal $meal): string
    {
        if (null !== $meal->getCustomTitle()) {
            return $meal->getCustomTitle();
        }

        $names = $meal->getRecipes()->map(static fn ($recipe) => $recipe->getName())->getValues();

        if ([] !== $names) {
            return implode(', ', $names);
        }

        return MealSlot::Lunch === $meal->getSlot() ? 'Déjeuner' : 'Dîner';
    }
}
