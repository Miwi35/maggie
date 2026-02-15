<?php

declare(strict_types=1);

namespace Maggie\Memory\Mcp\Tool;

use Maggie\Memory\Entity\Memory;
use Maggie\Memory\Message\StoreMemoryCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'store_memory', description: "Store a memory about the user. Use type 'factual' for preferences, habits, personal info (e.g. favorite food, name, job). Use type 'episodic' for notable events or conversations (e.g. a trip, an achievement).")]
class StoreMemoryTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(string $type, string $content, ?string $metadata = null): string
    {
        try {
            $envelope = $this->bus->dispatch(new StoreMemoryCommand(
                type: $type,
                content: $content,
                metadata: $metadata,
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
