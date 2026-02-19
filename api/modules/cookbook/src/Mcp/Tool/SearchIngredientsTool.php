<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Repository\IngredientRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'search_ingredients', description: 'Search food ingredients by name. Returns matching ingredients with id, name, and category.')]
class SearchIngredientsTool
{
    public function __construct(
        private readonly IngredientRepository $ingredientRepository,
    ) {
    }

    public function __invoke(string $query): string
    {
        $ingredients = $this->ingredientRepository->searchByName($query);

        $results = array_map(fn ($i) => [
            'id' => (string) $i->getId(),
            'name' => $i->getName(),
            'category' => $i->getCategory()->value,
        ], $ingredients);

        return json_encode(['ingredients' => $results], JSON_THROW_ON_ERROR);
    }
}
