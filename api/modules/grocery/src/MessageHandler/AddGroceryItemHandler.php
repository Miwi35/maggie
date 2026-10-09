<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
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
        $products = $this->productRepository->searchByName($user, $command->label);
        $matched = null;
        foreach ($products as $product) {
            if (mb_strtolower($product->getName()) === mb_strtolower($command->label)) {
                $matched = $product;
                break;
            }
        }

        $newProduct = null;

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

        // Set preferred store on newly created products
        $resolvedStore = $item->getStore();
        if (null !== $newProduct && null !== $resolvedStore && null === $newProduct->getPreferredStore()) {
            $newProduct->setPreferredStore($resolvedStore);
        }

        // Update preferred store on existing matched products if resolved store differs
        if (null !== $matched && null !== $resolvedStore && $matched->getPreferredStore() !== $resolvedStore) {
            $matched->setPreferredStore($resolvedStore);
        }

        $unit = null !== $command->unit ? Unit::from($command->unit) : null;
        $quantity = $command->quantity;

        // A product with a packaging is counted in it: « Riz » alone is one pack.
        if (null === $unit && null !== $matched && null !== $matched->getPackagingUnit()) {
            $unit = $matched->getPackagingUnit();
            $quantity ??= 1.0;
        }

        $mergeable = null !== $matched ? $this->findMergeable($list, $matched, $unit) : null;
        if (null !== $mergeable) {
            $this->merge($mergeable, $quantity, $unit);
            $list->setUpdatedAt(new \DateTimeImmutable());
            $this->em->flush();

            if ($productUpdated) {
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

        $item->setQuantity($quantity);
        $item->setUnit($unit);

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

        return $list;
    }

    /**
     * The line an added product joins instead of doubling: same product, same
     * unit, not yet in the basket — a ticked line is never merged, the new need
     * must not hide in it (same key as `MealGrocerySync::findMergeable()`).
     */
    private function findMergeable(GroceryList $list, Product $product, ?Unit $unit): ?GroceryItem
    {
        foreach ($list->getItems() as $existing) {
            if ($existing->isChecked()) {
                continue;
            }

            // A line with no unit of a packaged product reads as that packaging (« 2 » is 2 packs).
            if ((string) $existing->getProduct()?->getId() === (string) $product->getId()
                && ($existing->getUnit() ?? $product->getPackagingUnit()) === $unit) {
                return $existing;
            }
        }

        return null;
    }

    private function merge(GroceryItem $existing, ?float $quantity, ?Unit $unit): void
    {
        $existing->setUnit($unit);

        // Without a quantity on either side there is nothing to count: the line is already there.
        if (null !== $quantity || null !== $existing->getQuantity()) {
            $existing->setQuantity(($existing->getQuantity() ?? 1.0) + ($quantity ?? 1.0));
        }

        // Added by hand, so wanted now: a line deferred by a meal's shelf life comes back.
        $existing->setBuyAfter(null);
    }
}
