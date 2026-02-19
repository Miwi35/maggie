<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Message\DeleteRecipeCommand;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\UseCase\DeleteRecipe;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteRecipeHandler
{
    public function __construct(
        private readonly DeleteRecipe $deleteRecipe,
        private readonly RecipeRepository $recipeRepository,
    ) {
    }

    public function __invoke(DeleteRecipeCommand $command): void
    {
        $recipe = $this->recipeRepository->find($command->recipeId)
            ?? throw new \DomainException("Recipe not found: {$command->recipeId}");

        $this->deleteRecipe->execute($recipe);
    }
}
