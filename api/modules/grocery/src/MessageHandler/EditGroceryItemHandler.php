<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Message\EditGroceryItemCommand;
use Maggie\Grocery\Message\UpdateProductCommand;
use Maggie\Grocery\Repository\ProductRepository;
use Maggie\Grocery\Repository\StoreRepository;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class EditGroceryItemHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
        private readonly ProductRepository $productRepository,
        private readonly StoreRepository $storeRepository,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(EditGroceryItemCommand $command): GroceryList
    {
        $item = $this->em->getRepository(GroceryItem::class)->find($command->groceryItemId)
            ?? throw new \DomainException("Grocery item not found: {$command->groceryItemId}");

        $list = $item->getGroceryList();
        $user = $list->getUser();

        if ((string) $user->getId() !== $command->userId) {
            throw new \DomainException('Access denied.');
        }

        $product = $item->getProduct();
        $productUpdated = false;
        $newProduct = null;
        $newStore = null;

        // Label change: update product name or find-or-create product
        if ($command->label !== null) {
            if ($product !== null) {
                if (mb_strtolower($product->getName()) !== mb_strtolower($command->label)) {
                    $product->setName($command->label);
                    $productUpdated = true;
                }
            } else {
                // No product linked — find or create one (same logic as AddGroceryItemHandler)
                $products = $this->productRepository->searchByName($command->label);
                $matched = null;
                foreach ($products as $p) {
                    if (mb_strtolower($p->getName()) === mb_strtolower($command->label)) {
                        $matched = $p;
                        break;
                    }
                }

                if ($matched !== null) {
                    $item->setProduct($matched);
                    $product = $matched;
                } else {
                    $newProduct = new Product();
                    $newProduct->setName($command->label);
                    $newProduct->setCategory(ProductCategory::Other);
                    $newProduct->setUser($user);
                    $this->em->persist($newProduct);
                    $item->setProduct($newProduct);
                    $product = $newProduct;
                }

                $item->setCustomLabel(null);
            }
        }

        // Category change
        if ($command->category !== null && $product !== null) {
            $cat = ProductCategory::tryFrom($command->category);
            if ($cat !== null && $cat !== $product->getCategory()) {
                $product->setCategory($cat);
                $productUpdated = true;
            }
        }

        // Quantity change
        if ($command->quantity !== null) {
            $item->setQuantity($command->quantity);
        }

        // Unit change
        if ($command->unit !== null) {
            $unit = Unit::from($command->unit);
            $item->setUnit($unit);
            if ($product !== null && $product->getDefaultUnit() !== $unit) {
                $product->setDefaultUnit($unit);
                $productUpdated = true;
            }
        }

        // Store resolution: storeId > storeName > keep current
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

        // Update product preferred store if store changed
        $resolvedStore = $item->getStore();
        if ($product !== null && $resolvedStore !== null && $product->getPreferredStore() !== $resolvedStore) {
            $product->setPreferredStore($resolvedStore);
            $productUpdated = true;
        }

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        // Dispatch ES indexing for changed entities
        if ($newProduct !== null) {
            $this->bus->dispatch(new IndexDocumentCommand(
                entityClass: Product::class,
                entityId: (string) $newProduct->getId(),
            ));
        } elseif ($productUpdated && $product !== null) {
            $this->bus->dispatch(new IndexDocumentCommand(
                entityClass: Product::class,
                entityId: (string) $product->getId(),
            ));
        }

        if ($newStore !== null) {
            $this->bus->dispatch(new IndexDocumentCommand(
                entityClass: Store::class,
                entityId: (string) $newStore->getId(),
            ));
        }

        // Dispatch UpdateProductCommand to trigger Product Mercure publish
        if ($product !== null && ($productUpdated || $newProduct !== null)) {
            $this->bus->dispatch(new UpdateProductCommand(
                productId: (string) $product->getId(),
            ));
        }

        return $list;
    }
}
