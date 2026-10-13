<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\Unit;

/**
 * Turns a recipe quantity into the packagings a product is bought in, rounded
 * up: 300 g of rice with a 500 g pack is 1 pack, 700 g is 2.
 *
 * Mass (g, kg) and volume (ml, cl, l) convert within their family; counted
 * units (piece, pack, jar…) only match themselves.
 */
final class Packaging
{
    /** Each convertible unit, as its family and its factor to the family's base unit. */
    private const FAMILIES = [
        'g' => ['mass', 1.0],
        'kg' => ['mass', 1000.0],
        'ml' => ['volume', 1.0],
        'cl' => ['volume', 10.0],
        'l' => ['volume', 1000.0],
    ];

    // Absorbs float noise so that 1 kg in 500 g packs stays 2, not 3.
    private const EPSILON = 1e-9;

    /** Null when the product has no packaging: the caller keeps the recipe unit. */
    public function packagedQuantity(Product $product, float $quantity, Unit $unit): ?PackagedQuantity
    {
        $packagingUnit = $product->getPackagingUnit();
        if (null === $packagingUnit) {
            return null;
        }

        $fraction = $this->fraction($product, $packagingUnit, $quantity, $unit);

        return null === $fraction
            ? new PackagedQuantity(1, $packagingUnit, false)
            : new PackagedQuantity($this->roundUp($fraction), $packagingUnit, true);
    }

    /**
     * What a line of a packaged product holds: a whole number of packagings,
     * at least one, whatever the unit the quantity arrives in. No unit reads as
     * the packaging, no quantity as one. Null when the product has no packaging.
     */
    public function inPackagings(Product $product, ?float $quantity, ?Unit $unit): ?int
    {
        $packagingUnit = $product->getPackagingUnit();
        if (null === $packagingUnit) {
            return null;
        }

        if (null === $quantity) {
            return 1;
        }

        return max(1, $this->packagedQuantity($product, $quantity, $unit ?? $packagingUnit)->count);
    }

    /**
     * The packagings that cover several quantities of one product together,
     * rounded up once: 300 g and 300 g of rice in 500 g packs are 2 packs. A
     * quantity that cannot be computed counts as one packaging.
     *
     * @param iterable<array{0: float|int, 1: Unit}> $quantities quantity, unit
     */
    public function packagedTotal(Product $product, iterable $quantities): ?int
    {
        $packagingUnit = $product->getPackagingUnit();
        if (null === $packagingUnit) {
            return null;
        }

        $total = 0.0;
        foreach ($quantities as [$quantity, $unit]) {
            $total += $this->fraction($product, $packagingUnit, (float) $quantity, $unit) ?? 1.0;
        }

        return max(1, $this->roundUp($total));
    }

    /** The quantity as a fraction of packagings, null when its unit cannot be turned into them. */
    private function fraction(Product $product, Unit $packagingUnit, float $quantity, Unit $unit): ?float
    {
        // Asked directly in what the product is bought in: 2 jars are 2 jars.
        if ($unit === $packagingUnit) {
            return $quantity;
        }

        $size = $product->getPackagingSize();
        $sizeUnit = $product->getPackagingSizeUnit();

        return null !== $size && null !== $sizeUnit && $size > 0 ? $this->ratio($quantity, $unit, $size, $sizeUnit) : null;
    }

    private function ratio(float $quantity, Unit $unit, float $size, Unit $sizeUnit): ?float
    {
        if ($unit === $sizeUnit) {
            return $quantity / $size;
        }

        $from = self::FAMILIES[$unit->value] ?? null;
        $to = self::FAMILIES[$sizeUnit->value] ?? null;
        if (null === $from || null === $to || $from[0] !== $to[0]) {
            return null;
        }

        return ($quantity * $from[1]) / ($size * $to[1]);
    }

    private function roundUp(float $value): int
    {
        return (int) ceil($value - self::EPSILON);
    }
}
