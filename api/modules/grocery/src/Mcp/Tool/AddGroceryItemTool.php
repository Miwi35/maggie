<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Message\AddGroceryItemCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'add_grocery_item', description: 'Add an item to the grocery list by label. Works for any product: food ingredients, household supplies, hygiene items, etc. If a matching product exists, its preferred store is auto-assigned. For unknown products, determine the store from store descriptions (e.g. potatoes → greengrocer, toilet paper → supermarket). Set storeId directly when you can determine it. Only ask the user when you genuinely cannot determine the right store.')]
class AddGroceryItemTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $label,
        ?float $quantity = null,
        ?string $unit = null,
        ?string $storeId = null,
        ?string $storeName = null,
        ?string $category = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();

            $envelope = $this->bus->dispatch(new AddGroceryItemCommand(
                userId: (string) $user->getId(),
                label: $label,
                quantity: $quantity,
                unit: $unit,
                storeId: $storeId,
                storeName: $storeName,
                category: $category,
            ));

            /** @var GroceryList $list */
            $list = $envelope->last(HandledStamp::class)->getResult();

            return json_encode([
                'success' => true,
                'itemCount' => $list->getItems()->count(),
            ], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
