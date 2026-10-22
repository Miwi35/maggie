<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Service\RecipeNeeds;
use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Exception\InvalidGroceryQuantityException;
use Maggie\Grocery\Message\AddGroceryItemCommand;
use Maggie\Grocery\Message\AddGroceryItemResult;
use Maggie\Grocery\Repository\GroceryListRepository;
use Maggie\Grocery\Repository\ProductRepository;
use Maggie\Grocery\Repository\StoreRepository;
use Maggie\Grocery\Service\DesiredQuantity;
use Maggie\Grocery\Service\GroceryLineNeeds;
use Maggie\Grocery\Service\Packaging;
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
        private readonly Packaging $packaging,
        private readonly GroceryLineNeeds $lineNeeds,
        private readonly DesiredQuantity $desired,
        private readonly RecipeNeeds $recipeNeeds,
    ) {
    }

    public function __invoke(AddGroceryItemCommand $command): AddGroceryItemResult
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

        $open = null !== $matched ? $this->findMergeable($list, $matched) : null;
        if ((null !== $command->quantity && $command->quantity <= 0) || (null !== $command->packagings && $command->packagings < 1)) {
            throw InvalidGroceryQuantityException::notPositive($open);
        }

        if (null !== $command->packagings) {
            if (null !== $command->quantity || null !== $command->unit) {
                throw InvalidGroceryQuantityException::exclusive($open);
            }
            if (null === $matched?->getPackagingUnit()) {
                throw InvalidGroceryQuantityException::noPackaging($open);
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
        $product = $item->getProduct();
        $packagingUnit = $matched?->getPackagingUnit();

        // What the week's meals need of the product (a product just created has no meals).
        $needs = null === $matched ? [] : $this->recipeNeeds->forProduct($user, $matched)['needs'];
        $recipeNeed = null === $product ? null : $this->desired->headline($product, $needs);

        // A product with a packaging is counted in it, whatever the unit it is
        // asked in. Asked a quantity, the packagings are that quantity rounded
        // up and the quantity stays wanted (« 1,4 kg » is 3 packs of 500 g);
        // asked nothing, its restock is the default, else what the meals need.
        $wanted = null;
        if (null !== $matched && null !== $packagingUnit) {
            if (null !== $command->packagings) {
                [$packs, $wanted] = $this->desired->ofPackagings($matched, $command->packagings);
            } elseif (null !== $quantity) {
                [$packs, $wanted] = $this->desired->ofQuantity($matched, $quantity, $unit);
            } elseif (($matched->getRestockQuantity() ?? 0) > 0) {
                [$packs, $wanted] = $this->desired->ofPackagings($matched, (int) $matched->getRestockQuantity());
            } elseif ([] !== $needs) {
                [$packs, $wanted] = $this->desired->ofPackagings($matched, (int) $this->packaging->packsFor($matched, $needs));
            } else {
                $packs = 1;
            }

            $quantity = (float) $packs;
            $unit = $packagingUnit;
        }

        $mergeable = null !== $matched ? $this->findMergeable($list, $matched) : null;
        if (null !== $mergeable) {
            $this->merge($mergeable, $quantity, $unit, $wanted);
            $list->setUpdatedAt(new \DateTimeImmutable());
            $this->em->flush();

            return $this->result($list, $mergeable, $product, $needs, $recipeNeed);
        }

        if (null !== $quantity && null === $packagingUnit) {
            // The quantity is a need of the product, in a base unit.
            $this->lineNeeds->apply($item, $quantity, $unit);
        } else {
            $item->setQuantity($quantity);
            $item->setUnit($unit);
            $item->setNeeded([] === $needs ? null : $needs);
            $item->applyDesired($wanted[0] ?? null, $wanted[1] ?? null);
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

        return $this->result($list, $item, $product, $needs, $recipeNeed);
    }

    /**
     * @param list<array{quantity: float, unit: ?string}> $needs
     * @param array{quantity: float, unit: ?string}|null  $recipeNeed
     */
    private function result(GroceryList $list, GroceryItem $item, ?Product $product, array $needs, ?array $recipeNeed): AddGroceryItemResult
    {
        $covers = null === $product || $this->desired->covers($product, $item->getQuantity(), $item->getUnit(), $needs);

        return new AddGroceryItemResult(
            $list,
            $item,
            $recipeNeed,
            $covers,
            $covers || null === $product->getPackagingUnit() ? null : $this->packaging->packsFor($product, $needs),
        );
    }

    /**
     * The line an added product joins instead of doubling: same product, not
     * yet in the basket — a ticked line is never merged, the new need must not
     * hide in it (same key as `MealGrocerySync::findMergeable()`). A product has
     * one line, whatever the unit of what it holds (MAG-380).
     */
    private function findMergeable(GroceryList $list, Product $product): ?GroceryItem
    {
        foreach ($list->getItems() as $existing) {
            if (!$existing->isChecked() && (string) $existing->getProduct()?->getId() === (string) $product->getId()) {
                return $existing;
            }
        }

        return null;
    }

    /** @param array{0: float, 1: Unit}|null $wanted what a packaged product's addition wants, in base units */
    private function merge(GroceryItem $existing, ?float $quantity, ?Unit $unit, ?array $wanted): void
    {
        $product = $existing->getProduct();

        if (null !== $product && null !== $product->getPackagingUnit()) {
            $current = (int) $this->packaging->inPackagings($product, $existing->getQuantity(), $existing->getUnit());
            $added = (int) ($quantity ?? 1.0);
            $existing->setQuantity((float) ($current + $added));
            $existing->setUnit($unit);
            $this->desired->addDesired($existing, $product, $added, $wanted, $current);
            $this->desired->enforce($existing);
        } elseif (null !== $quantity) {
            // Without a quantity there is nothing to count: the line is already there.
            $this->lineNeeds->apply($existing, $quantity, $unit);
        }

        // Added by hand, so wanted now: a line deferred by a meal's shelf life comes back.
        $existing->setBuyAfter(null);
    }
}
