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

        // Asked directly in what the product is bought in: 2 jars are 2 jars.
        if ($unit === $packagingUnit) {
            return new PackagedQuantity($this->roundUp($quantity), $packagingUnit, true);
        }

        $size = $product->getPackagingSize();
        $sizeUnit = $product->getPackagingSizeUnit();
        $ratio = null !== $size && null !== $sizeUnit && $size > 0 ? $this->ratio($quantity, $unit, $size, $sizeUnit) : null;

        if (null === $ratio) {
            return new PackagedQuantity(1, $packagingUnit, false);
        }

        return new PackagedQuantity($this->roundUp($ratio), $packagingUnit, true);
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
