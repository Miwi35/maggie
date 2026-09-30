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

        $product = $item->getProduct();
        if ($command->productId !== null) {
            $product = $this->productRepository->find($command->productId)
                ?? throw new \DomainException("Product not found: {$command->productId}");
        } elseif ($command->clears('productId')) {
            $product = null;
        }

        $customLabel = $item->getCustomLabel();
        if ($command->customLabel !== null) {
            $customLabel = $command->customLabel;
        } elseif ($command->clears('customLabel')) {
            $customLabel = null;
        }

        // Clearing must not leave the item with neither a product nor a label
        if (($command->clears('productId') || $command->clears('customLabel'))
            && $product === null
            && $customLabel === null
        ) {
            throw new \DomainException('A recurring grocery item needs a product or a custom label.');
        }

        if ($command->frequency !== null) {
            $item->setFrequency(RecurringFrequency::from($command->frequency));
        }
        $item->setProduct($product);
        $item->setCustomLabel($customLabel);
        if ($command->quantity !== null) {
            $item->setQuantity($command->quantity);
        } elseif ($command->clears('quantity')) {
            $item->setQuantity(null);
        }
        if ($command->unit !== null) {
            $item->setUnit(Unit::from($command->unit));
        } elseif ($command->clears('unit')) {
            $item->setUnit(null);
        }

        return $this->updateRecurringGroceryItem->execute($item);
    }
}
