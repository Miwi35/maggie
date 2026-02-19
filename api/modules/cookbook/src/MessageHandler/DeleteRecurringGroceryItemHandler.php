<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Message\DeleteRecurringGroceryItemCommand;
use Maggie\Cookbook\Repository\RecurringGroceryItemRepository;
use Maggie\Cookbook\UseCase\DeleteRecurringGroceryItem;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteRecurringGroceryItemHandler
{
    public function __construct(
        private readonly DeleteRecurringGroceryItem $deleteRecurringGroceryItem,
        private readonly RecurringGroceryItemRepository $recurringGroceryItemRepository,
    ) {
    }

    public function __invoke(DeleteRecurringGroceryItemCommand $command): void
    {
        $item = $this->recurringGroceryItemRepository->find($command->recurringGroceryItemId)
            ?? throw new \DomainException("Recurring grocery item not found: {$command->recurringGroceryItemId}");

        $this->deleteRecurringGroceryItem->execute($item);
    }
}
