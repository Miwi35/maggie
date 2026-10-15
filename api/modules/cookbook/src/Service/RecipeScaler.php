<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Entity\Recipe;
use Maggie\Grocery\Enum\Unit;

/**
 * A recipe's quantities for a number of guests (MAG-482): × guests / servings,
 * rounded to what a cook reads — the one rule behind the grocery list's needs
 * and the recipe sheet's simulation (`GET /api/recipes/{id}?guests=N`,
 * `get_recipe guests`).
 *
 * The quantities are indicative (owner, 8 Oct.): the list buys packagings
 * anyway, so the rounding only has to read well, never to be exact.
 *
 * - g, ml, cl: to 5 from 100 up, to the unit from 10 up, to a tenth below;
 * - kg, l: to 0.05;
 * - pieces, cans, jars and every other counted unit: to the half.
 *
 * Never down to nothing: a quantity rounds to at least one step.
 */
final class RecipeScaler
{
    /** The scale factor of the recipe for that many guests: 6 guests of a recipe for 4 is 1.5. */
    public static function factor(Recipe $recipe, int $guests): float
    {
        return $guests / max(1, $recipe->getServings());
    }

    /** The quantity × factor, rounded as the cook reads it. Factor 1 leaves it as written. */
    public static function scale(float $quantity, Unit $unit, float $factor): float
    {
        if (abs($factor - 1.0) < 1e-9) {
            return $quantity;
        }

        $scaled = $quantity * $factor;
        $step = self::step($scaled, $unit);

        return max($step, round(round($scaled / $step) * $step, 2));
    }

    private static function step(float $quantity, Unit $unit): float
    {
        return match ($unit) {
            Unit::Gram, Unit::Milliliter, Unit::Centiliter => match (true) {
                $quantity >= 100 => 5.0,
                $quantity >= 10 => 1.0,
                default => 0.1,
            },
            Unit::Kilogram, Unit::Liter => 0.05,
            default => 0.5,
        };
    }
}
