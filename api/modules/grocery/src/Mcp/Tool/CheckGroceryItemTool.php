<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Message\CheckGroceryItemCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'check_grocery_item', description: 'Toggle the checked state of a grocery item. Set checked=true when bought, false to uncheck.')]
class CheckGroceryItemTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $groceryItemId,
        bool $checked = true,
    ): string {
        try {
            $user = $this->userContext->requireUser();

            $this->bus->dispatch(new CheckGroceryItemCommand(
                groceryItemId: $groceryItemId,
                userId: (string) $user->getId(),
                checked: $checked,
            ));

            return json_encode(['success' => true, 'checked' => $checked], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
