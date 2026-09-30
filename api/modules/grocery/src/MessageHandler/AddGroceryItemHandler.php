<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Message\AddGroceryItemCommand;
use Maggie\Grocery\Repository\GroceryListRepository;
use Maggie\Grocery\Repository\ProductRepository;
use Maggie\Grocery\Repository\StoreRepository;
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
        $products = $this->productRepository->searchByName($user, $command->label);
        $matched = null;
        foreach ($products as $product) {
            if (mb_strtolower($product->getName()) === mb_strtolower($command->label)) {
                $matched = $product;
                break;
            }
        }

        $newProduct = null;

        $productUpdated = false;

        if (null !== $matched) {
            $item->setProduct($matched);
            if (null !== $matched->getPreferredStore()) {
                $item->setStore($matched->getPreferredStore());
            }
            // Update category on existing product if provided and different
            if (null !== $command->category) {
                $cat = ProductCategory::tryFrom($command->category);
                if (null !== $cat && $cat !== $matched->getCategory()) {
                    $matched->setCategory($cat);
                    $productUpdated = true;
                }
            }
        } else {
            $newProduct = new Product();
            $newProduct->setName($command->label);
            $newProduct->setCategory(
                null !== $command->category
                    ? (ProductCategory::tryFrom($command->category) ?? ProductCategory::Other)
                    : ProductCategory::Other,
            );
            $newProduct->setUser($user);
            $this->em->persist($newProduct);
            $item->setProduct($newProduct);
        }

        // Resolve store: storeId takes priority, then storeName (match or create)
        $newStore = null;
        if (null !== $command->storeId) {
            $store = $this->storeRepository->find($command->storeId);
            if (null !== $store) {
                $item->setStore($store);
            }
        } elseif (null !== $command->storeName && '' !== $command->storeName) {
            $store = $this->storeRepository->findByNameAndUser($command->storeName, $user);
            if (null !== $store) {
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
        if (null !== $newProduct && null !== $resolvedStore && null === $newProduct->getPreferredStore()) {
            $newProduct->setPreferredStore($resolvedStore);
        }

        // Update preferred store on existing matched products if resolved store differs
        if (null !== $matched && null !== $resolvedStore && $matched->getPreferredStore() !== $resolvedStore) {
            $matched->setPreferredStore($resolvedStore);
            $productUpdated = true;
        }

        if (null !== $command->quantity) {
            $item->setQuantity($command->quantity);
        }
        if (null !== $command->unit) {
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

        if (null !== $newProduct) {
            $this->bus->dispatch(new IndexDocumentCommand(
                entityClass: Product::class,
                entityId: (string) $newProduct->getId(),
            ));
        } elseif ($productUpdated && null !== $matched) {
            $this->bus->dispatch(new IndexDocumentCommand(
                entityClass: Product::class,
                entityId: (string) $matched->getId(),
            ));
        }

        if (null !== $newStore) {
            $this->bus->dispatch(new IndexDocumentCommand(
                entityClass: Store::class,
                entityId: (string) $newStore->getId(),
            ));
        }

        return $list;
    }
}
