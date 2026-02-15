<?php

declare(strict_types=1);

namespace Maggie\Memory\Mcp\Tool;

use Maggie\Memory\Repository\MemoryRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_user_profile', description: "Get the user's profile: all stored factual memories (preferences, habits, personal info). Use this to recall what you know about the user.")]
class GetUserProfileTool
{
    public function __construct(
        private readonly MemoryRepository $memoryRepository,
    ) {
    }

    public function __invoke(): string
    {
        $memories = $this->memoryRepository->findAllFactual();

        $result = array_map(fn ($m) => [
            'id' => (string) $m->getId(),
            'content' => $m->getContent(),
            'metadata' => $m->getMetadata(),
            'createdAt' => $m->getCreatedAt()->format('c'),
        ], $memories);

        return json_encode(['factual_memories' => $result, 'count' => count($result)], JSON_THROW_ON_ERROR);
    }
}
