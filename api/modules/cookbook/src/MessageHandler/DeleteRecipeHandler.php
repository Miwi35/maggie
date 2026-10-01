<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Message\DeleteRecipeCommand;
use Maggie\Cookbook\Message\UpdateMealCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\UseCase\DeleteRecipe;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
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

        // A deleted recipe leaves the meals that served it, and the shopping it
        // asked for leaves the list (MAG-167). Upcoming meals go through the
        // ordinary meal update — it syncs the list, rewrites the summary and
        // publishes and reindexes the meal — with the recipe taken out.
        foreach ($this->mealRepository->findUpcomingByRecipe($recipe) as $meal) {
            $this->bus->dispatch(new UpdateMealCommand(
                mealId: (string) $meal->getId(),
                recipeIds: $this->otherRecipeIds($meal, $recipe),
            ));
        }

        // Past meals are shopping already done: the join rows cascade with the
        // recipe, the list is left alone, and only the search document needs
        // to forget the recipe.
        $past = $this->mealRepository->findPastByRecipe($recipe);

        $this->deleteRecipe->execute($recipe);

        foreach ($past as $meal) {
            $this->bus->dispatch(new IndexDocumentCommand(Meal::class, (string) $meal->getId()));
        }
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
