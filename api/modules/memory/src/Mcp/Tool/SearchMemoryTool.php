<?php

declare(strict_types=1);

namespace Maggie\Memory\Mcp\Tool;

use Maggie\Memory\Entity\MemoryType;
use Maggie\Memory\Repository\MemoryRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'search_memory', description: 'Search through stored memories using full-text search. Returns matching memories ranked by relevance. Optionally filter by type (factual or episodic).')]
class SearchMemoryTool
{
    public function __construct(
        private readonly MemoryRepository $memoryRepository,
    ) {
    }

    public function __invoke(string $query, ?string $type = null): string
    {
        $memoryType = null;
        if ($type !== null) {
            $memoryType = MemoryType::tryFrom($type);
            if ($memoryType === null) {
                return json_encode(['error' => 'Invalid type. Valid: factual, episodic'], JSON_THROW_ON_ERROR);
            }
        }

        $memories = $this->memoryRepository->search($query, $memoryType);

        $result = array_map(fn ($m) => [
            'id' => (string) $m->getId(),
            'type' => $m->getType()->value,
            'content' => $m->getContent(),
            'metadata' => $m->getMetadata(),
            'createdAt' => $m->getCreatedAt()->format('c'),
        ], $memories);

        return json_encode(['memories' => $result, 'count' => count($result)], JSON_THROW_ON_ERROR);
    }
}
