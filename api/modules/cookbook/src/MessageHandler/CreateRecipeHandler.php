<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\RecipeIngredient;
use Maggie\Cookbook\Enum\Unit;
use Maggie\Cookbook\Message\CreateRecipeCommand;
use Maggie\Cookbook\Repository\IngredientRepository;
use Maggie\Cookbook\UseCase\CreateRecipe;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateRecipeHandler
{
    public function __construct(
        private readonly CreateRecipe $createRecipe,
        private readonly UserRepository $userRepository,
        private readonly IngredientRepository $ingredientRepository,
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

        if ($command->notes !== null) {
            $recipe->setNotes($command->notes);
        }

        if ($command->ingredients !== null) {
            foreach ($command->ingredients as $item) {
                $ingredient = $this->ingredientRepository->find($item['ingredientId'])
                    ?? throw new \DomainException("Ingredient not found: {$item['ingredientId']}");

                $ri = new RecipeIngredient();
                $ri->setIngredient($ingredient);
                $ri->setQuantity($item['quantity']);
                $ri->setUnit(Unit::from($item['unit']));
                $recipe->addIngredient($ri);
            }
        }

        return $this->createRecipe->execute($recipe);
    }
}
