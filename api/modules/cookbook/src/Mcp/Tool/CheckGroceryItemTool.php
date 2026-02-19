<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Message\CheckGroceryItemCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'check_grocery_item', description: 'Toggle the checked state of a grocery item. Set checked=true when bought, false to uncheck.')]
class CheckGroceryItemTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(
        string $groceryItemId,
        bool $checked = true,
    ): string {
        try {
            $this->bus->dispatch(new CheckGroceryItemCommand(
                groceryItemId: $groceryItemId,
                checked: $checked,
            ));

            return json_encode(['success' => true, 'checked' => $checked], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
