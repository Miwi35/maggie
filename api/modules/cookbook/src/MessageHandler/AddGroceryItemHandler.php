<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\GroceryItem;
use Maggie\Cookbook\Entity\GroceryList;
use Maggie\Cookbook\Enum\GroceryItemSource;
use Maggie\Cookbook\Enum\Unit;
use Maggie\Cookbook\Message\AddGroceryItemCommand;
use Maggie\Cookbook\Repository\GroceryListRepository;
use Maggie\Cookbook\Repository\ProductRepository;
use Maggie\Cookbook\Repository\StoreRepository;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class AddGroceryItemHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly GroceryListRepository $groceryListRepository,
        private readonly ProductRepository $productRepository,
        private readonly StoreRepository $storeRepository,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(AddGroceryItemCommand $command): GroceryList
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $list = $this->groceryListRepository->findOrCreateForUser($user);

        $item = new GroceryItem();
        $item->setSource(GroceryItemSource::from($command->source));

        // Search products by label for auto-matching
        $products = $this->productRepository->searchByName($command->label);
        $matched = null;
        foreach ($products as $product) {
            if (mb_strtolower($product->getName()) === mb_strtolower($command->label)) {
                $matched = $product;
                break;
            }
        }

        if ($matched !== null) {
            $item->setProduct($matched);
            if ($matched->getPreferredStore() !== null) {
                $item->setStore($matched->getPreferredStore());
            }
        } else {
            $item->setCustomLabel($command->label);
        }

        // Override store if explicitly provided
        if ($command->storeId !== null) {
            $store = $this->storeRepository->find($command->storeId);
            if ($store !== null) {
                $item->setStore($store);
            }
        }

        if ($command->quantity !== null) {
            $item->setQuantity($command->quantity);
        }
        if ($command->unit !== null) {
            $item->setUnit(Unit::from($command->unit));
        }

        $list->addItem($item);
        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        return $list;
    }
}
