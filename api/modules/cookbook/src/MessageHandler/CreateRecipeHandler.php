<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\RecipeIngredient;
use Maggie\Cookbook\Message\CreateRecipeCommand;
use Maggie\Cookbook\Repository\IngredientRepository;
use Maggie\Cookbook\Service\IngredientFromCiqualResolver;
use Maggie\Cookbook\UseCase\CreateRecipe;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\EntityBroadcaster;
use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Enum\Unit;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateRecipeHandler
{
    public function __construct(
        private readonly CreateRecipe $createRecipe,
        private readonly UserRepository $userRepository,
        private readonly IngredientRepository $ingredientRepository,
        private readonly IngredientFromCiqualResolver $ciqualResolver,
        private readonly EntityBroadcaster $entityBroadcaster,
    ) {
    }

    public function __invoke(CreateRecipeCommand $command): Recipe
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        /** @var list<Ingredient> $createdIngredients */
        $createdIngredients = [];

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
                $ingredient = $this->resolveIngredient($item, $user, $createdIngredients);

                $ri = new RecipeIngredient();
                $ri->setIngredient($ingredient);
                $ri->setQuantity($item['quantity']);
                $ri->setUnit(Unit::from($item['unit']));
                $recipe->addIngredient($ri);
            }
        }

        $recipe = $this->createRecipe->execute($recipe);

        // The handler returns the recipe: the ingredients created on the way
        // would otherwise be neither indexed nor published (MAG-182).
        foreach ($createdIngredients as $ingredient) {
            $this->entityBroadcaster->broadcast($ingredient);
        }

        return $recipe;
    }

    /**
     * @param array{quantity: float, unit: string, ingredientId?: string, ciqualAlimCode?: string} $item
     * @param list<Ingredient>                                                                     $created
     */
    private function resolveIngredient(array $item, User $user, array &$created): Ingredient
    {
        if (isset($item['ciqualAlimCode'])) {
            return $this->ciqualResolver->resolve($item['ciqualAlimCode'], $user, $created);
        }

        if (isset($item['ingredientId'])) {
            return $this->ingredientRepository->find($item['ingredientId'])
                ?? throw new \DomainException("Ingredient not found: {$item['ingredientId']}");
        }

        throw new \DomainException('Each ingredient must have either ingredientId or ciqualAlimCode.');
    }
}
