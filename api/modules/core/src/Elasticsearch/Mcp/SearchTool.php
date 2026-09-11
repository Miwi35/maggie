<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Mcp;

use Maggie\Core\Elasticsearch\SearchService;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'search', description: 'Full-text search across all Maggie data (events, tasks, recipes, products, grocery lists, notifications). Returns matching results with highlights.')]
class SearchTool
{
    public function __construct(
        private readonly SearchService $searchService,
        private readonly McpUserContext $userContext,
    ) {}

    public function __invoke(string $query, ?string $types = null, int $limit = 10): string
    {
        $user = $this->userContext->getUser();
        if ($user === null) {
            return json_encode(['error' => MissingMcpUserException::MESSAGE], JSON_THROW_ON_ERROR);
        }

        $indices = $types !== null
            ? array_map('trim', explode(',', $types))
            : null;

        try {
            $results = $this->searchService->search(
                query: $query,
                userId: (string) $user->getId(),
                indices: $indices,
                size: $limit,
            );

            return json_encode($results, JSON_THROW_ON_ERROR);
        } catch (\Throwable $e) {
            return json_encode([
                'error' => 'Search failed: ' . $e->getMessage(),
                'total' => 0,
                'results' => [],
            ], JSON_THROW_ON_ERROR);
        }
    }
}
