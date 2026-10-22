<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\Unit;

/**
 * The quantity wanted of a packaged product, apart from its packagings
 * (MAG-573): « 1,4 kg » of rice in 500 g packs is 3 packs, and the line keeps
 * saying 1,4 kg. The packagings rule — they are rounded up, so
 * `quantity ≥ ceil(wanted / size)` — and the wanted quantity is in base units.
 * It is unknown (null) when the product does not say what a pack holds, or
 * when what was asked is not in the same family as the pack's content.
 *
 * @phpstan-import-type Need from Needs
 */
final class DesiredQuantity
{
    public function __construct(
        private readonly Packaging $packaging,
    ) {
    }

    /**
     * Packagings and wanted quantity for a quantity asked in a unit. Asked in
     * the packaging itself (or with no unit), it is a number of packagings.
     *
     * @return array{0: int, 1: array{0: float, 1: Unit}|null}
     */
    public function ofQuantity(Product $product, float $quantity, ?Unit $unit): array
    {
        $packs = (int) $this->packaging->inPackagings($product, $quantity, $unit);

        if (null === $unit || $unit === $product->getPackagingUnit()) {
            return [$packs, $this->ofPackagings($product, $packs)[1]];
        }

        [$base, $baseUnit] = Needs::toBase($quantity, $unit);
        $size = $this->packaging->packSize($product);

        return [$packs, null !== $size && $size[1] === $baseUnit ? [$base, $baseUnit] : null];
    }

    /** @return array{0: int, 1: array{0: float, 1: Unit}|null} */
    public function ofPackagings(Product $product, int $packs): array
    {
        $size = $this->packaging->packSize($product);

        return [$packs, null === $size ? null : [$packs * $size[0], $size[1]]];
    }

    /**
     * Brings the wanted quantity back under what the packagings hold: it
     * cannot ask more than they cover, and is dropped when it cannot be
     * compared to them.
     */
    public function enforce(GroceryItem $item): void
    {
        $product = $item->getProduct();
        $desired = $item->getDesiredQuantity();
        if (null === $desired) {
            return;
        }

        $size = null === $product ? null : $this->packaging->packSize($product);
        $packs = $item->getQuantity();
        if (null === $size || null === $packs || $item->getDesiredUnit() !== $size[1]) {
            $item->applyDesired(null, null);

            return;
        }

        if ($desired > $packs * $size[0] + Needs::EPSILON) {
            $item->applyDesired($packs * $size[0], $size[1]);
        }
    }

    /** Sets the wanted quantity from a number of packagings and the packagings from nothing else. */
    public function setPackagings(GroceryItem $item, Product $product, int $packs): void
    {
        $item->setQuantity((float) $packs);
        $item->setUnit($product->getPackagingUnit());

        // A wanted quantity the packagings still cover is kept: « + » adds a
        // pack without changing what was asked. One they no longer cover comes down.
        $desired = $item->getDesiredQuantity();
        $size = $this->packaging->packSize($product);
        if (null === $desired || null === $size || $item->getDesiredUnit() !== $size[1]) {
            $whole = $this->ofPackagings($product, $packs)[1];
            $item->applyDesired($whole[0] ?? null, $whole[1] ?? null);

            return;
        }

        $this->enforce($item);
    }

    /**
     * Adds what a second addition wants to a line's wanted quantity.
     *
     * @param array{0: float, 1: Unit}|null $added
     */
    public function addDesired(GroceryItem $item, Product $product, int $addedPacks, ?array $added, int $packsBefore): void
    {
        $before = null === $item->getDesiredQuantity() ? null : [$item->getDesiredQuantity(), $item->getDesiredUnit()];
        if (null === $before && null === $added) {
            return;
        }

        $before ??= $this->ofPackagings($product, $packsBefore)[1];
        $added ??= $this->ofPackagings($product, $addedPacks)[1];

        if (null === $before || null === $added || $before[1] !== $added[1]) {
            $item->applyDesired(null, null);

            return;
        }

        $item->applyDesired($before[0] + $added[0], $before[1]);
    }

    /**
     * The need to show beside a quantity: the one in the family of the pack's
     * content (« 1200 g »), else the first.
     *
     * @param list<Need> $needs
     *
     * @return Need|null
     */
    public function headline(Product $product, array $needs): ?array
    {
        $size = $this->packaging->packSize($product);
        foreach ($needs as $need) {
            if (null !== $size && $need['unit'] === $size[1]->value) {
                return $need;
            }
        }

        return $needs[0] ?? null;
    }

    /**
     * Whether a line covers the needs of the planned meals: packagings counted
     * once on the total need for a packaged product, else the line's quantity
     * against the need in the same unit. A need in a unit the line cannot be
     * compared to is not held against it.
     *
     * @param list<Need> $needs
     */
    public function covers(Product $product, ?float $quantity, ?Unit $unit, array $needs): bool
    {
        if ([] === $needs) {
            return true;
        }

        if (null !== $product->getPackagingUnit()) {
            return ($quantity ?? 0.0) >= (float) $this->packaging->packsFor($product, $needs);
        }

        if (null === $quantity) {
            return false;
        }

        [$have, $baseUnit] = Needs::toBase($quantity, $unit);
        foreach ($needs as $need) {
            if ($need['unit'] === $baseUnit?->value && $have + Needs::EPSILON < $need['quantity']) {
                return false;
            }
        }

        return true;
    }
}
