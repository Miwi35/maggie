<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Support;

use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Message\ChooseMealGroceriesCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Planning a meal puts nothing on the list since MAG-300: tests that need its
 * lines there make the choice the owner makes, for every ingredient of a recipe.
 *
 * The host class provides `em()` and `getFixture()`.
 */
trait ChoosesMealGroceriesTrait
{
    private function chooseEveryIngredientOf(string $mealId, string $recipeRef, string $userId): void
    {
        $recipe = $this->em()->find(Recipe::class, $this->getFixture($recipeRef)->getId()) ?? throw new \LogicException("No recipe {$recipeRef}.");

        $chosen = [];
        foreach ($recipe->getIngredients() as $line) {
            $chosen[(string) $line->getIngredient()->getId()] = null;
        }

        self::getContainer()->get(MessageBusInterface::class)->dispatch(new ChooseMealGroceriesCommand(mealId: $mealId, userId: $userId, chosen: $chosen));
        $this->em()->clear();
    }
}
