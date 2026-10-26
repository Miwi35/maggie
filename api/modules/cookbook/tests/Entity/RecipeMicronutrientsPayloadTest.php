<?php

namespace Maggie\Cookbook\Tests\Entity;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\RecipeIngredient;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use PHPUnit\Framework\TestCase;

/** MAG-648: an open recipe sheet redraws its micronutrients from the Mercure update of its lines or servings. */
class RecipeMicronutrientsPayloadTest extends TestCase
{
    private function soup(): Recipe
    {
        $user = new User();
        $orange = (new Ingredient())->setName('Orange')->setCategory(ProductCategory::Produce)->setUser($user)->setMicronutrientsPer100g(['vitaminC' => 50.0]);
        $recipe = (new Recipe())->setName('Soupe')->setServings(2)->setUser($user);
        $recipe->addIngredient((new RecipeIngredient())->setIngredient($orange)->setQuantity(160.0)->setUnit(Unit::Gram));

        return $recipe;
    }

    public function testTheMicronutrientsFollowAnUpdateOfTheLinesOrTheServings(): void
    {
        foreach (['ingredients', 'servings'] as $changed) {
            $payload = $this->soup()->toMercurePayload([$changed]);

            self::assertSame('vitaminC', $payload['micronutrientsPerServing'][0]['key'], $changed);
            self::assertEqualsWithDelta(40.0, $payload['micronutrientsPerServing'][0]['amount'], 0.001);
            self::assertSame(50, $payload['micronutrientsPerServing'][0]['referencePercent']);
        }
    }

    public function testAnUpdateOfTheNameLeavesThemOut(): void
    {
        self::assertArrayNotHasKey('micronutrientsPerServing', $this->soup()->toMercurePayload(['name']));
    }
}
