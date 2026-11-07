<?php

namespace Maggie\Cookbook\Tests\Service;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\RecipeIngredient;
use Maggie\Cookbook\Service\RecipeNutrition;
use Maggie\Grocery\Enum\Unit;
use PHPUnit\Framework\TestCase;

/** One serving's kcal and macros (MAG-618, MAG-748): the lines the sheet counts, divided by the servings. */
class RecipeNutritionTest extends TestCase
{
    private static function line(Recipe $recipe, float $quantity, Unit $unit, ?float $kcal, ?float $protein = null, ?float $carbs = null, ?float $fat = null): void
    {
        $food = (new Ingredient())->setKcalPer100g($kcal)->setProteinPer100g($protein)->setCarbsPer100g($carbs)->setFatPer100g($fat);
        $recipe->addIngredient((new RecipeIngredient())->setIngredient($food)->setQuantity($quantity)->setUnit($unit));
    }

    public function testTheMacrosOfOneServingFollowTheCountedLines(): void
    {
        $recipe = (new Recipe())->setName('Bol')->setServings(2);
        self::line($recipe, 200.0, Unit::Gram, 350.0, 12.0, 70.0, 1.5);
        self::line($recipe, 0.5, Unit::Liter, 18.0);
        // Counted in pieces: no weight, left out, whatever its figures.
        self::line($recipe, 3.0, Unit::Piece, 100.0, 10.0, 10.0, 10.0);

        self::assertSame(['kcal' => 395, 'proteinG' => 12.0, 'carbsG' => 70.0, 'fatG' => 1.5], RecipeNutrition::perServing($recipe));
        self::assertSame(395, RecipeNutrition::kcalPerServing($recipe));
    }

    public function testARecipeWithNoCountedLineHasNoFigures(): void
    {
        $recipe = (new Recipe())->setName('Mystère')->setServings(2);
        self::line($recipe, 100.0, Unit::Gram, null, 5.0);

        self::assertNull(RecipeNutrition::perServing($recipe));
        self::assertNull(RecipeNutrition::kcalPerServing($recipe));
    }
}
