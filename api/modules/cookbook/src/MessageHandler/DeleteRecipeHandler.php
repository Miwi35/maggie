<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Message\DeleteRecipeCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\Service\MealGrocerySync;
use Maggie\Cookbook\UseCase\DeleteRecipe;
use Maggie\Grocery\Service\GroceryListBroadcaster;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteRecipeHandler
{
    public function __construct(
        private readonly DeleteRecipe $deleteRecipe,
        private readonly RecipeRepository $recipeRepository,
        private readonly MealRepository $mealRepository,
        private readonly MealGrocerySync $mealGrocerySync,
        private readonly GroceryListBroadcaster $groceryListBroadcaster,
    ) {
    }

    public function __invoke(DeleteRecipeCommand $command): void
    {
        $recipe = $this->recipeRepository->find($command->recipeId)
            ?? throw new \DomainException("Recipe not found: {$command->recipeId}");

        // A deleted recipe leaves the meals that served it (the join rows
        // cascade) and so must leave their share of the list. Upcoming meals
        // are detached first, in memory, so the sync sees what they now need.
        $upcoming = $this->mealRepository->findUpcomingByRecipe($recipe);
        foreach ($upcoming as $meal) {
            $meal->removeRecipe($recipe);
        }

        $this->deleteRecipe->execute($recipe);

        $list = null;
        foreach ($upcoming as $meal) {
            $list = $this->mealGrocerySync->sync($meal) ?? $list;
        }
        $this->groceryListBroadcaster->broadcast($list);
    }
}
