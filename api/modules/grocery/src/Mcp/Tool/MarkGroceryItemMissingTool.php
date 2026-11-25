<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Message\MarkGroceryItemMissingCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'mark_grocery_item_missing', description: 'Marks an unchecked item missing: it moves to the product\'s fallback store if any, else stays. missing=false moves it back to the store it was missing from. For an item the store does not have: neither bought nor removed.')]
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
