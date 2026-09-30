<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Repository\IngredientRepository;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'search_ingredients', description: 'Search food ingredients by name. Returns matching ingredients with id, name, and category.')]
class SearchIngredientsTool
{
    public function __construct(
        private readonly IngredientRepository $ingredientRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $query): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $ingredients = $this->ingredientRepository->searchByName($user, $query);

        $results = array_map(fn ($i) => [
            'id' => (string) $i->getId(),
            'name' => $i->getName(),
            'category' => $i->getCategory()->value,
        ], $ingredients);

        return json_encode(['ingredients' => $results], JSON_THROW_ON_ERROR);
    }
}
