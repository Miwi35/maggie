<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_recipe', description: 'Get a recipe by ID with its ingredients.')]
class GetRecipeTool
{
    public function __construct(
        private readonly RecipeRepository $recipeRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $recipeId): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $recipe = $this->recipeRepository->findOneForUser($recipeId, $user);
        if ($recipe === null) {
            return json_encode(['error' => "Recipe not found: {$recipeId}"], JSON_THROW_ON_ERROR);
        }

        $ingredients = [];
        foreach ($recipe->getIngredients() as $ri) {
            $ingredients[] = [
                'ingredient' => $ri->getIngredient()->getName(),
                'ingredientId' => (string) $ri->getIngredient()->getId(),
                'quantity' => $ri->getQuantity(),
                'unit' => $ri->getUnit()->value,
            ];
        }

        return json_encode([
            'recipe' => [
                'id' => (string) $recipe->getId(),
                'name' => $recipe->getName(),
                'servings' => $recipe->getServings(),
                'tags' => $recipe->getTags(),
                'notes' => $recipe->getNotes(),
                'ingredients' => $ingredients,
            ],
        ], JSON_THROW_ON_ERROR);
    }
}
