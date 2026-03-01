<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Message\AddGroceryItemCommand;
use Maggie\Grocery\Repository\GroceryListRepository;
use Maggie\Grocery\Repository\ProductRepository;
use Maggie\Grocery\Repository\StoreRepository;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class AddGroceryItemHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
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

        $newProduct = null;

        $productUpdated = false;

        if ($matched !== null) {
            $item->setProduct($matched);
            if ($matched->getPreferredStore() !== null) {
                $item->setStore($matched->getPreferredStore());
            }
            // Update category on existing product if provided and different
            if ($command->category !== null) {
                $cat = ProductCategory::tryFrom($command->category);
                if ($cat !== null && $cat !== $matched->getCategory()) {
                    $matched->setCategory($cat);
                    $productUpdated = true;
                }
            }
        } else {
            $newProduct = new Product();
            $newProduct->setName($command->label);
            $newProduct->setCategory(
                $command->category !== null
                    ? (ProductCategory::tryFrom($command->category) ?? ProductCategory::Other)
                    : ProductCategory::Other,
            );
            $newProduct->setUser($user);
            $this->em->persist($newProduct);
            $item->setProduct($newProduct);
        }

        // Resolve store: storeId takes priority, then storeName (match or create)
        $newStore = null;
        if ($command->storeId !== null) {
            $store = $this->storeRepository->find($command->storeId);
            if ($store !== null) {
                $item->setStore($store);
            }
        } elseif ($command->storeName !== null && $command->storeName !== '') {
            $store = $this->storeRepository->findByNameAndUser($command->storeName, $user);
            if ($store !== null) {
                $item->setStore($store);
            } else {
                $newStore = new Store();
                $newStore->setName($command->storeName);
                $newStore->setVisitOrder(0);
                $newStore->setUser($user);
                $this->em->persist($newStore);
                $item->setStore($newStore);
            }
        }

        // Set preferred store on newly created products
        $resolvedStore = $item->getStore();
        if ($newProduct !== null && $resolvedStore !== null && $newProduct->getPreferredStore() === null) {
            $newProduct->setPreferredStore($resolvedStore);
        }

        // Update preferred store on existing matched products if resolved store differs
        if ($matched !== null && $resolvedStore !== null && $matched->getPreferredStore() !== $resolvedStore) {
            $matched->setPreferredStore($resolvedStore);
            $productUpdated = true;
        }

        if ($command->quantity !== null) {
            $item->setQuantity($command->quantity);
        }
        if ($command->unit !== null) {
            $item->setUnit(Unit::from($command->unit));
        }

        $maxPosition = 0;
        foreach ($list->getItems() as $existing) {
            if ($existing->getPosition() > $maxPosition) {
                $maxPosition = $existing->getPosition();
            }
        }
        $item->setPosition($maxPosition + 1);

        $list->addItem($item);
        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        if ($newProduct !== null) {
            $this->bus->dispatch(new IndexDocumentCommand(
                entityClass: Product::class,
                entityId: (string) $newProduct->getId(),
            ));
        } elseif ($productUpdated && $matched !== null) {
            $this->bus->dispatch(new IndexDocumentCommand(
                entityClass: Product::class,
                entityId: (string) $matched->getId(),
            ));
        }

        if ($newStore !== null) {
            $this->bus->dispatch(new IndexDocumentCommand(
                entityClass: Store::class,
                entityId: (string) $newStore->getId(),
            ));
        }

        return $list;
    }
}
