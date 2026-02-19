<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\RecurringGroceryItem;
use Maggie\Cookbook\Enum\RecurringFrequency;
use Maggie\Cookbook\Enum\Unit;
use Maggie\Cookbook\Message\CreateRecurringGroceryItemCommand;
use Maggie\Cookbook\Repository\ProductRepository;
use Maggie\Cookbook\UseCase\CreateRecurringGroceryItem;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateRecurringGroceryItemHandler
{
    public function __construct(
        private readonly CreateRecurringGroceryItem $createRecurringGroceryItem,
        private readonly UserRepository $userRepository,
        private readonly ProductRepository $productRepository,
    ) {
    }

    public function __invoke(CreateRecurringGroceryItemCommand $command): RecurringGroceryItem
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $item = new RecurringGroceryItem();
        $item->setUser($user);
        $item->setFrequency(RecurringFrequency::from($command->frequency));

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

        return $this->createRecurringGroceryItem->execute($item);
    }
}
