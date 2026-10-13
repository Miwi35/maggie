<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Enum\Unit;

/**
 * The one rule that turns what a line is needed for into what to buy (MAG-380).
 *
 * A line keeps its needs in base units (`needed`) and what to buy in
 * `quantity`/`unit`:
 * - a product without packaging reads its needs: 800 g, or the first of
 *   several units when they cannot be added (« 300 g + 2 pièces » keeps 300 g
 *   as its quantity and both in `needed`);
 * - a packaged product reads packagings, counted once on the total need
 *   ({@see Packaging::packsFor()}). The line moves by the difference the need
 *   made, so the packagings the owner added or took off by hand stay.
 *
 * The conversion happens whenever the need changes: a packaging added to the
 * product later converts the line at the next change.
 */
final class GroceryLineNeeds
{
    public function __construct(
        private readonly Packaging $packaging,
    ) {
    }

    /**
     * The needs the line holds now: its own, or — for a line written before
     * needs were kept — its quantity when the product has no packaging.
     *
     * @return list<array{quantity: float, unit: ?string}>
     */
    public function of(GroceryItem $item): array
    {
        if (null !== $item->getNeeded()) {
            return $item->getNeeded();
        }

        return null === $item->getProduct()?->getPackagingUnit()
            ? Needs::of($item->getQuantity(), $item->getUnit())
            : [];
    }

    /** Adds a signed quantity to the line's needs and moves what to buy by what it changes. */
    public function apply(GroceryItem $item, float $quantity, ?Unit $unit): void
    {
        $before = $this->of($item);

        $this->replace($item, $before, Needs::add($before, $quantity, $unit));
    }

    /**
     * Gives the line other needs, moving what to buy by what it changes.
     *
     * @param list<array{quantity: float, unit: ?string}> $before
     * @param list<array{quantity: float, unit: ?string}> $after
     */
    public function replace(GroceryItem $item, array $before, array $after): void
    {
        $item->setNeeded($after);

        $product = $item->getProduct();
        $packagingUnit = $product?->getPackagingUnit();

        if (null === $product || null === $packagingUnit) {
            if ([] === $after) {
                $item->setQuantity(null);

                return;
            }

            [$quantity, $unit] = Needs::readable($after[0]['quantity'], null === $after[0]['unit'] ? null : Unit::from($after[0]['unit']));
            $item->setQuantity($quantity);
            $item->setUnit($unit);

            return;
        }

        // A line not yet counted in the packaging: its quantity is converted
        // first, as the packagings it already stands for.
        $current = match (true) {
            null === $item->getQuantity() => 0.0,
            $item->getUnit() === $packagingUnit => $item->getQuantity(),
            default => (float) $this->packaging->inPackagings($product, $item->getQuantity(), $item->getUnit()),
        };

        $packs = max(0, $current + $this->packaging->packsFor($product, $after) - $this->packaging->packsFor($product, $before));

        $item->setQuantity($packs > Needs::EPSILON ? (float) $packs : null);
        $item->setUnit($packagingUnit);
    }
}
