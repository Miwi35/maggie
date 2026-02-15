<?php

declare(strict_types=1);

namespace Maggie\Memory\Mcp\Tool;

use Maggie\Memory\Entity\Memory;
use Maggie\Memory\Message\UpdateMemoryCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'update_memory', description: 'Update the content of an existing memory by its ID.')]
class UpdateMemoryTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(string $id, string $content): string
    {
        try {
            $envelope = $this->bus->dispatch(new UpdateMemoryCommand(
                memoryId: $id,
                content: $content,
            ));

            /** @var Memory $memory */
            $memory = $envelope->last(HandledStamp::class)->getResult();

            return json_encode([
                'success' => true,
                'memory' => [
                    'id' => (string) $memory->getId(),
                    'type' => $memory->getType()->value,
                    'content' => $memory->getContent(),
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
