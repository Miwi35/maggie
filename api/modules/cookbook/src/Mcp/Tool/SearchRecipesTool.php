<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Repository\RecipeRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'search_recipes', description: 'Search recipes by name or tag. Provide either query (name search) or tag (tag search).')]
class SearchRecipesTool
{
    public function __construct(
        private readonly RecipeRepository $recipeRepository,
    ) {
    }

    public function __invoke(
        ?string $query = null,
        ?string $tag = null,
    ): string {
        if ($query !== null) {
            $recipes = $this->recipeRepository->searchByName($query);
        } elseif ($tag !== null) {
            $recipes = $this->recipeRepository->searchByTags($tag);
        } else {
            return json_encode(['error' => 'Provide either query or tag parameter'], JSON_THROW_ON_ERROR);
        }

        $results = array_map(fn ($r) => [
            'id' => (string) $r->getId(),
            'name' => $r->getName(),
            'servings' => $r->getServings(),
            'tags' => $r->getTags(),
        ], $recipes);

        return json_encode(['recipes' => $results], JSON_THROW_ON_ERROR);
    }
}
