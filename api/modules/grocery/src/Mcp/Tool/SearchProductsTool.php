<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Repository\ProductRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'search_products', description: 'Search all products (food and non-food) by name. Returns matching products with id, name, category, and type.')]
class SearchProductsTool
{
    public function __construct(
        private readonly ProductRepository $productRepository,
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

        $products = $this->productRepository->searchByName($user, $query);

        $results = array_map(fn ($p) => [
            'id' => (string) $p->getId(),
            'name' => $p->getName(),
            'category' => $p->getCategory()->value,
            'type' => strtolower((new \ReflectionClass($p))->getShortName()),
        ], $products);

        return json_encode(['products' => $results], JSON_THROW_ON_ERROR);
    }
}
