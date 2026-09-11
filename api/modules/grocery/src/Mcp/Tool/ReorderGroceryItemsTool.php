<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Message\ReorderGroceryItemsCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'reorder_grocery_items', description: 'Reorder grocery items by setting their position. Pass an array of {id, position} pairs.')]
class ReorderGroceryItemsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    /**
     * @param array<array{id: string, position: int}> $items
     */
    public function __invoke(array $items): string
    {
        $user = $this->userContext->getUser();

        if ($user === null) {
            return json_encode(['error' => MissingMcpUserException::MESSAGE], JSON_THROW_ON_ERROR);
        }

        try {
            $this->bus->dispatch(new ReorderGroceryItemsCommand(
                userId: (string) $user->getId(),
                items: $items,
            ));

            return json_encode(['success' => true], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
