<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Message\DeleteRecipeCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'delete_recipe', description: 'Delete a recipe by ID. Meals that have no other recipe are deleted with it (their shopping leaves the grocery list); meals with other recipes keep them. Returns deletedMeals, the number of meals deleted.')]
class DeleteRecipeTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly RecipeRepository $recipeRepository,
        private readonly MealRepository $mealRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $recipeId): string
    {
        try {
            $recipe = $this->recipeRepository->findOneForUser($recipeId, $this->userContext->requireUser());
            if (null === $recipe) {
                return json_encode(['error' => "Recipe not found: {$recipeId}"], JSON_THROW_ON_ERROR);
            }

            $deletedMeals = $this->mealRepository->countServedOnlyBy($recipe);

            $this->bus->dispatch(new DeleteRecipeCommand(recipeId: $recipeId));

            return json_encode(['success' => true, 'deletedMeals' => $deletedMeals], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
