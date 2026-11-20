<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Exception\InvalidGroceryQuantityException;
use Maggie\Grocery\Message\AddGroceryItemCommand;
use Maggie\Grocery\Message\AddGroceryItemResult;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'add_grocery_item', description: 'Adds an item by label. A matching product\'s preferred store is assigned, else storeId or storeName. A product already on the list (not ticked) accumulates on its line, even in another unit. quantity with unit, or packagings (whole number >= 1, packaged products only), never both. A packaged product is counted in packagings, rounded up (asked quantity kept in desiredQuantity); with neither: restockQuantity, else the 7-day meal need, else 1. Returns productId, recipeNeed (base units, null when none), coversWeek, packagingsToCover.')]
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
        ?int $packagings = null,
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
                packagings: $packagings,
            ));

            /** @var AddGroceryItemResult $result */
            $result = $envelope->last(HandledStamp::class)->getResult();

            return json_encode([...$result->toArray(), 'itemCount' => $result->list->getItems()->count()], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            $error = ['error' => $cause->getMessage()];
            if ($cause instanceof InvalidGroceryQuantityException) {
                $error['item'] = $cause->item;
            }

            return json_encode($error, JSON_THROW_ON_ERROR);
        }
    }
}
