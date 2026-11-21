<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Message\DeleteMealCommand;
use Maggie\Cookbook\Message\DeleteRecipeCommand;
use Maggie\Cookbook\Message\UpdateMealCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\UseCase\DeleteRecipe;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class DeleteRecipeHandler
{
    public function __construct(
        private readonly DeleteRecipe $deleteRecipe,
        private readonly RecipeRepository $recipeRepository,
        private readonly MealRepository $mealRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(DeleteRecipeCommand $command): void
    {
        $recipe = $this->recipeRepository->find($command->recipeId)
            ?? throw new \DomainException("Recipe not found: {$command->recipeId}");

        // A meal whose only recipe this is has nothing left to serve: it goes
        // the way a cancelled meal does, past or upcoming — the shopping it
        // asked for leaves the list, the agenda and the search index forget it.
        // The other upcoming meals go through the ordinary meal update, with
        // the recipe taken out (MAG-167): it rewrites the summary and
        // publishes and reindexes the meal, and leaves the list as the owner chose it.
        foreach ($this->mealRepository->findUpcomingByRecipe($recipe) as $meal) {
            if (1 === $meal->getRecipes()->count()) {
                $this->bus->dispatch(new DeleteMealCommand(mealId: (string) $meal->getId()));

                continue;
            }

            $this->bus->dispatch(new UpdateMealCommand(
                mealId: (string) $meal->getId(),
                recipeIds: $this->otherRecipeIds($meal, $recipe),
            ));
        }

        // A past meal that keeps other recipes is shopping already done: the list
        // is left alone, and the join row that cascades with the recipe is announced
        // by the projection (the meal is published and reindexed without it).
        foreach ($this->mealRepository->findPastByRecipe($recipe) as $meal) {
            if (1 === $meal->getRecipes()->count()) {
                $this->bus->dispatch(new DeleteMealCommand(mealId: (string) $meal->getId()));
            }
        }

        $this->deleteRecipe->execute($recipe);
    }

    /** @return string[] */
    private function otherRecipeIds(Meal $meal, Recipe $deleted): array
    {
        return array_values(array_map(
            static fn (Recipe $r) => (string) $r->getId(),
            array_filter(
                $meal->getRecipes()->toArray(),
                static fn (Recipe $r) => (string) $r->getId() !== (string) $deleted->getId(),
            ),
        ));
    }
}
