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
        return self::perServing($recipe)['kcal'] ?? null;
    }

    /**
     * The kcal and the three macros of one serving (MAG-748), from the lines {@see kcalPerServing()}
     * counts: a counted line with no protein, carbs or fat figure brings none of it. Null when no
     * line can be counted.
     *
     * @return array{kcal: int, proteinG: float, carbsG: float, fatG: float}|null
     */
    public static function perServing(Recipe $recipe): ?array
    {
        $servings = $recipe->getServings();
        if ($servings <= 0) {
            return null;
        }

        $kcal = $protein = $carbs = $fat = 0.0;
        $counted = 0;
        foreach ($recipe->getIngredients() as $line) {
            $grams = self::GRAMS[$line->getUnit()->value] ?? null;
            $food = $line->getIngredient();
            if (null === $grams || null === $food->getKcalPer100g()) {
                continue;
            }
            $hundreds = $line->getQuantity() * $grams / 100;
            $kcal += $hundreds * $food->getKcalPer100g();
            $protein += $hundreds * ($food->getProteinPer100g() ?? 0.0);
            $carbs += $hundreds * ($food->getCarbsPer100g() ?? 0.0);
            $fat += $hundreds * ($food->getFatPer100g() ?? 0.0);
            ++$counted;
        }

        if (0 === $counted) {
            return null;
        }

        return [
            'kcal' => (int) round($kcal / $servings),
            'proteinG' => round($protein / $servings, 1),
            'carbsG' => round($carbs / $servings, 1),
            'fatG' => round($fat / $servings, 1),
        ];
    }
}
