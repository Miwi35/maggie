<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\GroceryItem;
use Maggie\Cookbook\Entity\GroceryList;
use Maggie\Cookbook\Enum\GroceryItemSource;
use Maggie\Cookbook\Enum\Unit;
use Maggie\Cookbook\Message\AddGroceryItemCommand;
use Maggie\Cookbook\Repository\GroceryListRepository;
use Maggie\Cookbook\Repository\ProductRepository;
use Maggie\Cookbook\UseCase\UpdateGroceryList;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class AddGroceryItemHandler
{
    public function __construct(
        private readonly UpdateGroceryList $updateGroceryList,
        private readonly GroceryListRepository $groceryListRepository,
        private readonly ProductRepository $productRepository,
    ) {
    }

    public function __invoke(AddGroceryItemCommand $command): GroceryList
    {
        $list = $this->groceryListRepository->find($command->groceryListId)
            ?? throw new \DomainException("Grocery list not found: {$command->groceryListId}");

        $item = new GroceryItem();
        $item->setSource(GroceryItemSource::Manual);

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

        $list->addItem($item);
        $list->setUpdatedAt(new \DateTimeImmutable());

        return $this->updateGroceryList->execute($list);
    }
}
