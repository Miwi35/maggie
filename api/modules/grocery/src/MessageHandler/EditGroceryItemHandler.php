<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Message\EditGroceryItemCommand;
use Maggie\Grocery\Repository\ProductRepository;
use Maggie\Grocery\Repository\StoreRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class EditGroceryItemHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly ProductRepository $productRepository,
        private readonly StoreRepository $storeRepository,
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

        // Label change: update product name or find-or-create product
        if (null !== $command->label) {
            if (null !== $product) {
                if (mb_strtolower($product->getName()) !== mb_strtolower($command->label)) {
                    $product->setName($command->label);
                }
            } else {
                // No product linked — find or create one (same logic as AddGroceryItemHandler)
                $products = $this->productRepository->searchByName($user, $command->label);
                $matched = null;
                foreach ($products as $p) {
                    if (mb_strtolower($p->getName()) === mb_strtolower($command->label)) {
                        $matched = $p;
                        break;
                    }
                }

                if (null !== $matched) {
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
        if (null !== $command->category && null !== $product) {
            $cat = ProductCategory::tryFrom($command->category);
            if (null !== $cat && $cat !== $product->getCategory()) {
                $product->setCategory($cat);
            }
        }

        // Quantity change
        if (null !== $command->quantity) {
            $item->setQuantity($command->quantity);
        }

        // Unit change
        if (null !== $command->unit) {
            $unit = Unit::from($command->unit);
            $item->setUnit($unit);
            if (null !== $product && $product->getDefaultUnit() !== $unit) {
                $product->setDefaultUnit($unit);
            }
        }

        // Store resolution: storeId > storeName > keep current
        if (null !== $command->storeId) {
            $store = $this->storeRepository->find($command->storeId);
            if (null !== $store && (string) $store->getUser()->getId() === $command->userId) {
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

        // Update product preferred store if store changed
        $resolvedStore = $item->getStore();
        if (null !== $product && null !== $resolvedStore && $product->getPreferredStore() !== $resolvedStore) {
            $product->setPreferredStore($resolvedStore);
        }

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        return $list;
    }
}
