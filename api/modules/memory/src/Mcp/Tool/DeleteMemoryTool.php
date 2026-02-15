<?php

declare(strict_types=1);

namespace Maggie\Memory\Mcp\Tool;

use Maggie\Memory\Message\DeleteMemoryCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'delete_memory', description: 'Delete a memory by its ID. Use this to remove outdated or incorrect memories.')]
class DeleteMemoryTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(string $id): string
    {
        try {
            $this->bus->dispatch(new DeleteMemoryCommand(
                memoryId: $id,
            ));

            return json_encode(['success' => true, 'deleted' => $id], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
