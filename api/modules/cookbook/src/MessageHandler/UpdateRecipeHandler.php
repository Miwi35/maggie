<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\RecipeIngredient;
use Maggie\Cookbook\Message\UpdateRecipeCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\Service\MealTitle;
use Maggie\Cookbook\Service\RecipeLineIngredient;
use Maggie\Cookbook\Service\RecipeSteps;
use Maggie\Cookbook\Service\RecipeUtensils;
use Maggie\Cookbook\UseCase\UpdateRecipe;
use Maggie\Grocery\Enum\Unit;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateRecipeHandler
{
    public function __construct(
        private readonly UpdateRecipe $updateRecipe,
        private readonly RecipeRepository $recipeRepository,
        private readonly MealRepository $mealRepository,
        private readonly RecipeLineIngredient $lineIngredient,
        private readonly RecipeUtensils $recipeUtensils,
        private readonly RecipeSteps $recipeSteps,
    ) {
    }

    public function __invoke(UpdateRecipeCommand $command): Recipe
    {
        $recipe = $this->recipeRepository->find($command->recipeId)
            ?? throw new \DomainException("Recipe not found: {$command->recipeId}");

        if (null !== $command->name) {
            $recipe->setName($command->name);
        }
        if (null !== $command->servings) {
            $recipe->setServings($command->servings);
        }
        if (null !== $command->tags) {
            $recipe->setTags($command->tags);
        }
        if (null !== $command->notes) {
            $recipe->setNotes($command->notes);
        } elseif ($command->clears('notes')) {
            $recipe->setNotes(null);
        }

        if (null !== $command->ingredients) {
            $user = $recipe->getUser();
            $recipe->clearIngredients();
            foreach ($command->ingredients as $item) {
                $ingredient = $this->lineIngredient->resolve($item, $user);

                $ri = new RecipeIngredient();
                $ri->setIngredient($ingredient);
                $ri->setQuantity($item['quantity']);
                $ri->setUnit(Unit::from($item['unit']));
                $recipe->addIngredient($ri);
            }
        }

        if (null !== $command->utensils) {
            $recipe->replaceUtensils($this->recipeUtensils->resolve($command->utensils, $recipe->getUser()));
        }

        // After the ingredients and the utensils: a step reads what the recipe has.
        if (null !== $command->steps) {
            $this->recipeSteps->replace($recipe, $command->steps);
        }
        $this->recipeSteps->assertConsistent($recipe);

        $recipe->setUpdatedAt(new \DateTimeImmutable());

        // A meal is titled after its recipes: a renamed recipe renames its meals,
        // in the same flush as the recipe (MAG-378).
        if (null !== $command->name) {
            foreach ($this->mealRepository->findByRecipe($recipe) as $meal) {
                $meal->setSummary(MealTitle::of($meal));
            }
        }

        // The meals planned with this recipe keep what the owner chose for them: the list
        // is not re-derived from the new quantities (MAG-300).
        return $this->updateRecipe->execute($recipe);
    }
}
