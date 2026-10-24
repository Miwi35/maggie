<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Grocery\Enum\ProductStockState;

/**
 * The ingredients of a meal's recipes the owner is low on or out of (MAG-564):
 * the one rule behind « manque : parmesan » on the Accueil, so every card that
 * speaks of what is missing counts it the same way.
 */
final class MissingIngredients
{
    /**
     * One entry per product, in the order the recipes list them.
     *
     * @return list<Ingredient>
     */
    public static function ofMeal(Meal $meal): array
    {
        $missing = [];

        foreach ($meal->getRecipes() as $recipe) {
            foreach ($recipe->getIngredients() as $line) {
                $ingredient = $line->getIngredient();
                if (ProductStockState::InStock !== $ingredient->getStockState()) {
                    $missing[(string) $ingredient->getId()] ??= $ingredient;
                }
            }
        }

        return array_values($missing);
    }
}
