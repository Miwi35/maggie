<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Message\AddRecurringGroceryItemCommand;
use Maggie\Grocery\Repository\RecurringGroceryItemRepository;
use Maggie\Grocery\UseCase\AddRecurringGroceryItem;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * Returns the list so the Mercure and Elasticsearch middlewares publish and
 * reindex it: the open screens get the line without reloading.
 */
#[AsMessageHandler]
class AddRecurringGroceryItemHandler
{
    public function __construct(
        private readonly AddRecurringGroceryItem $addRecurringGroceryItem,
        private readonly RecurringGroceryItemRepository $recurringGroceryItemRepository,
    ) {
    }

    public function __invoke(AddRecurringGroceryItemCommand $command): ?GroceryList
    {
        $item = $this->recurringGroceryItemRepository->find($command->itemId)
            ?? throw new \DomainException("Recurring grocery item not found: {$command->itemId}");

        return $this->addRecurringGroceryItem->execute($item);
    }
}
