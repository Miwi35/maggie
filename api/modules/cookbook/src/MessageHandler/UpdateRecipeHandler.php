<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\RecipeIngredient;
use Maggie\Cookbook\Message\UpdateRecipeCommand;
use Maggie\Cookbook\Repository\IngredientRepository;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\Service\IngredientFromCiqualResolver;
use Maggie\Cookbook\UseCase\UpdateRecipe;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Enum\Unit;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateRecipeHandler
{
    public function __construct(
        private readonly UpdateRecipe $updateRecipe,
        private readonly RecipeRepository $recipeRepository,
        private readonly IngredientRepository $ingredientRepository,
        private readonly IngredientFromCiqualResolver $ciqualResolver,
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
                $ingredient = $this->resolveIngredient($item, $user);

                $ri = new RecipeIngredient();
                $ri->setIngredient($ingredient);
                $ri->setQuantity($item['quantity']);
                $ri->setUnit(Unit::from($item['unit']));
                $recipe->addIngredient($ri);
            }
        }

        $recipe->setUpdatedAt(new \DateTimeImmutable());

        return $this->updateRecipe->execute($recipe);
    }

    /** @param array{quantity: float, unit: string, ingredientId?: string, ciqualAlimCode?: string} $item */
    private function resolveIngredient(array $item, User $user): Ingredient
    {
        if (isset($item['ciqualAlimCode'])) {
            return $this->ciqualResolver->resolve($item['ciqualAlimCode'], $user);
        }

        if (isset($item['ingredientId'])) {
            return $this->ingredientRepository->find($item['ingredientId'])
                ?? throw new \DomainException("Ingredient not found: {$item['ingredientId']}");
        }

        throw new \DomainException('Each ingredient must have either ingredientId or ciqualAlimCode.');
    }
}
