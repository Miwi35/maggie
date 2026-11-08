<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Message\MarkGroceryItemMissingCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'mark_grocery_item_missing', description: 'Mark an unchecked grocery item as missing in the store it was planned in (out of stock there). The item moves to its product\'s fallback store when one exists, otherwise it stays in the same store. missing=false cancels: the item goes back to the store it was missing from.')]
class MarkGroceryItemMissingTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $groceryItemId,
        bool $missing = true,
    ): string {
        try {
            $user = $this->userContext->requireUser();

            $this->bus->dispatch(new MarkGroceryItemMissingCommand(
                groceryItemId: $groceryItemId,
                userId: (string) $user->getId(),
                missing: $missing,
            ));

            return json_encode(['success' => true, 'missing' => $missing], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
