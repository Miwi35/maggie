<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Entity\Recipe;

/**
 * What one serving of a recipe brings, as the recipe sheet reads it (MAG-618): weights and volumes
 * count as grams (a millilitre of food weighs about a gram), a count (pieces, bunches) has no
 * weight the app knows and is left out, and so is a product with no nutrition. The same rule as
 * `nutritionPerServing` in `admin/src/modules/cookbook/recipeSheet.ts`.
 */
final class RecipeNutrition
{
    private const GRAMS = ['g' => 1.0, 'kg' => 1000.0, 'ml' => 1.0, 'cl' => 10.0, 'l' => 1000.0];

    /** Null when no line of the recipe can be counted. */
    public static function kcalPerServing(Recipe $recipe): ?int
    {
        $servings = $recipe->getServings();
        if ($servings <= 0) {
            return null;
        }

        $total = 0.0;
        $counted = 0;
        foreach ($recipe->getIngredients() as $line) {
            $grams = self::GRAMS[$line->getUnit()->value] ?? null;
            $kcal = $line->getIngredient()->getKcalPer100g();
            if (null === $grams || null === $kcal) {
                continue;
            }
            $total += $line->getQuantity() * $grams / 100 * $kcal;
            ++$counted;
        }

        return 0 === $counted ? null : (int) round($total / $servings);
    }
}
