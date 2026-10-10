<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Repository\GroceryListRepository;

/** The one place that puts a product on a user's grocery list. */
class GroceryListItems
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly GroceryListRepository $groceryListRepository,
    ) {
    }

    /**
     * Adds to the open line of the product when there is one in the same unit,
     * else appends a line at the end, in the product's usual store. Flushes.
     */
    public function addProduct(User $user, Product $product, int|float|null $quantity, ?Unit $unit, GroceryItemSource $source): GroceryList
    {
        $list = $this->groceryListRepository->findOrCreateForUser($user);

        $maxPosition = 0;
        $mergeable = null;
        foreach ($this->linesOf($list) as $existing) {
            $maxPosition = max($maxPosition, $existing->getPosition());

            // A ticked line is already in the basket: raising it would hide the new need.
            if (null === $mergeable && !$existing->isChecked() && $this->isLineOf($existing, $product, $unit)) {
                $mergeable = $existing;
            }
        }

        if (null !== $mergeable) {
            if (null !== $quantity) {
                $mergeable->setQuantity(($mergeable->getQuantity() ?? 0) + $quantity);
            }
            $mergeable->setUnit($unit);
        } else {
            $item = new GroceryItem();
            $item->setProduct($product);
            $item->setSource($source);
            $item->setStore($product->getPreferredStore());
            $item->setQuantity($quantity);
            $item->setUnit($unit);
            $item->setPosition($maxPosition + 1);
            $list->addItem($item);
        }

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        return $list;
    }

    /** Appends a line that is only words, no product behind it, at the end of the list. Flushes. */
    public function addLabelled(User $user, string $label, int|float|null $quantity, ?Unit $unit, GroceryItemSource $source): GroceryList
    {
        $list = $this->groceryListRepository->findOrCreateForUser($user);

        $maxPosition = 0;
        foreach ($this->linesOf($list) as $existing) {
            $maxPosition = max($maxPosition, $existing->getPosition());
        }

        $item = new GroceryItem();
        $item->setCustomLabel($label);
        $item->setSource($source);
        $item->setQuantity($quantity);
        $item->setUnit($unit);
        $item->setPosition($maxPosition + 1);
        $list->addItem($item);

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        return $list;
    }

    /** An unchecked line for the product, whatever its unit, is already waiting on the list. */
    public function hasOpenLineOf(User $user, Product $product): bool
    {
        foreach ($this->linesOf($this->groceryListRepository->findOrCreateForUser($user)) as $existing) {
            if (!$existing->isChecked() && (string) $existing->getProduct()?->getId() === (string) $product->getId()) {
                return true;
            }
        }

        return false;
    }

    /** An unchecked line with the same words is already waiting on the list. */
    public function hasOpenLineLabelled(User $user, string $label): bool
    {
        foreach ($this->linesOf($this->groceryListRepository->findOrCreateForUser($user)) as $existing) {
            if (!$existing->isChecked() && $existing->getLabel() === $label) {
                return true;
            }
        }

        return false;
    }

    /**
     * The list's lines, read through the repository rather than through
     * `$list->getItems()`: a lazy ghost proxy can leave the PersistentCollection
     * uninitialized and report no elements when the database has rows
     * (EndErrandHandler documents the same trap).
     *
     * @return GroceryItem[]
     */
    private function linesOf(GroceryList $list): array
    {
        return $this->em->getRepository(GroceryItem::class)->findBy(['groceryList' => $list]);
    }

    private function isLineOf(GroceryItem $item, Product $product, ?Unit $unit): bool
    {
        if ((string) $item->getProduct()?->getId() !== (string) $product->getId()) {
            return false;
        }

        // Quantities of different units cannot be added; a line with no
        // quantity at all can take the new one.
        return $item->getUnit() === $unit || null === $item->getQuantity();
    }
}
