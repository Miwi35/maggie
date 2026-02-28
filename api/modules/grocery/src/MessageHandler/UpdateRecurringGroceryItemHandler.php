<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Enum\RecurringFrequency;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Message\UpdateRecurringGroceryItemCommand;
use Maggie\Grocery\Repository\ProductRepository;
use Maggie\Grocery\Repository\RecurringGroceryItemRepository;
use Maggie\Grocery\UseCase\UpdateRecurringGroceryItem;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateRecurringGroceryItemHandler
{
    public function __construct(
        private readonly UpdateRecurringGroceryItem $updateRecurringGroceryItem,
        private readonly RecurringGroceryItemRepository $recurringGroceryItemRepository,
        private readonly ProductRepository $productRepository,
    ) {
    }

    public function __invoke(UpdateRecurringGroceryItemCommand $command): RecurringGroceryItem
    {
        $item = $this->recurringGroceryItemRepository->find($command->recurringGroceryItemId)
            ?? throw new \DomainException("Recurring grocery item not found: {$command->recurringGroceryItemId}");

        if ($command->frequency !== null) {
            $item->setFrequency(RecurringFrequency::from($command->frequency));
        }
        if ($command->productId !== null) {
            $product = $this->productRepository->find($command->productId)
                ?? throw new \DomainException("Product not found: {$command->productId}");
            $item->setProduct($product);
        }
        if ($command->customLabel !== null) {
            $item->setCustomLabel($command->customLabel);
        }
        if ($command->quantity !== null) {
            $item->setQuantity($command->quantity);
        }
        if ($command->unit !== null) {
            $item->setUnit(Unit::from($command->unit));
        }

        return $this->updateRecurringGroceryItem->execute($item);
    }
}
