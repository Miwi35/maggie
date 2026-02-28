<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Grocery\Repository\ProductRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'search_products', description: 'Search all products (food and non-food) by name. Returns matching products with id, name, category, and type.')]
class SearchProductsTool
{
    public function __construct(
        private readonly ProductRepository $productRepository,
    ) {
    }

    public function __invoke(string $query): string
    {
        $products = $this->productRepository->searchByName($query);

        $results = array_map(fn ($p) => [
            'id' => (string) $p->getId(),
            'name' => $p->getName(),
            'category' => $p->getCategory()->value,
            'type' => strtolower((new \ReflectionClass($p))->getShortName()),
        ], $products);

        return json_encode(['products' => $results], JSON_THROW_ON_ERROR);
    }
}
