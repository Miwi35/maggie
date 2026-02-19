<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\GroceryList;
use Maggie\Cookbook\Message\AddGroceryItemCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'add_grocery_item', description: 'Add a manual item to an existing grocery list. Provide either productId (to reference a product) or customLabel (free text).')]
class AddGroceryItemTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(
        string $groceryListId,
        ?string $productId = null,
        ?string $customLabel = null,
        ?float $quantity = null,
        ?string $unit = null,
    ): string {
        try {
            $envelope = $this->bus->dispatch(new AddGroceryItemCommand(
                groceryListId: $groceryListId,
                productId: $productId,
                customLabel: $customLabel,
                quantity: $quantity,
                unit: $unit,
            ));

            /** @var GroceryList $list */
            $list = $envelope->last(HandledStamp::class)->getResult();

            return json_encode([
                'success' => true,
                'itemCount' => $list->getItems()->count(),
            ], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
