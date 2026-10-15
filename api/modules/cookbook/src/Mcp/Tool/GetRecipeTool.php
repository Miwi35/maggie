<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\Service\RecipeScaler;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_recipe', description: 'Get a recipe by ID with its ingredients. guests (1 or more) answers the quantities scaled to that many people — × guests / servings, rounded as a cook reads them — without changing the recipe; the answer then says scaledFor and scaleFactor.')]
class GetRecipeTool
{
    public function __construct(
        private readonly RecipeRepository $recipeRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $recipeId, ?int $guests = null): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        if (null !== $guests && $guests < 1) {
            return json_encode(['error' => 'guests must be a whole number of at least 1.'], JSON_THROW_ON_ERROR);
        }

        $recipe = $this->recipeRepository->findOneForUser($recipeId, $user);
        if (null === $recipe) {
            return json_encode(['error' => "Recipe not found: {$recipeId}"], JSON_THROW_ON_ERROR);
        }

        $factor = null === $guests ? 1.0 : RecipeScaler::factor($recipe, $guests);

        $ingredients = [];
        foreach ($recipe->getIngredients() as $ri) {
            $ingredients[] = [
                'ingredient' => $ri->getIngredient()->getName(),
                'ingredientId' => (string) $ri->getIngredient()->getId(),
                'quantity' => RecipeScaler::scale($ri->getQuantity(), $ri->getUnit(), $factor),
                'unit' => $ri->getUnit()->value,
            ];
        }

        $answer = [
            'id' => (string) $recipe->getId(),
            'name' => $recipe->getName(),
            'servings' => $recipe->getServings(),
            'tags' => $recipe->getTags(),
            'notes' => $recipe->getNotes(),
            'ingredients' => $ingredients,
        ];

        if (null !== $guests) {
            $answer += ['scaledFor' => $guests, 'scaleFactor' => round($factor, 4)];
        }

        return json_encode(['recipe' => $answer], JSON_THROW_ON_ERROR);
    }
}
