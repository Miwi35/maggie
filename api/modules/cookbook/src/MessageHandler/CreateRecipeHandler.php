<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\RecipeIngredient;
use Maggie\Cookbook\Message\CreateRecipeCommand;
use Maggie\Cookbook\Service\RecipeLineIngredient;
use Maggie\Cookbook\Service\RecipeSteps;
use Maggie\Cookbook\Service\RecipeUtensils;
use Maggie\Cookbook\UseCase\CreateRecipe;
use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Enum\Unit;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateRecipeHandler
{
    public function __construct(
        private readonly CreateRecipe $createRecipe,
        private readonly UserRepository $userRepository,
        private readonly RecipeLineIngredient $lineIngredient,
        private readonly RecipeUtensils $recipeUtensils,
        private readonly RecipeSteps $recipeSteps,
    ) {
    }

    public function __invoke(CreateRecipeCommand $command): Recipe
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $recipe = new Recipe();
        $recipe->setUser($user);
        $recipe->setName($command->name);
        $recipe->setServings($command->servings);
        $recipe->setTags($command->tags);

        if (null !== $command->notes) {
            $recipe->setNotes($command->notes);
        }

        if (null !== $command->ingredients) {
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
            $recipe->replaceUtensils($this->recipeUtensils->resolve($command->utensils, $user));
        }

        // After the ingredients and the utensils: a step reads what the recipe has.
        if (null !== $command->steps) {
            $this->recipeSteps->replace($recipe, $command->steps);
        }
        $this->recipeSteps->assertConsistent($recipe);

        return $this->createRecipe->execute($recipe);
    }
}
