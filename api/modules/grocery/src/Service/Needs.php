<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Maggie\Grocery\Enum\Unit;

/**
 * What a product is needed for, added up in a base unit (MAG-380).
 *
 * Mass is counted in grams and volume in millilitres; every other unit only
 * adds up with itself, so 300 g and 2 pieces of one product stay two entries
 * of the same line — « 300 g + 2 pièces » — instead of two lines. A need is
 * `{quantity, unit}`, the unit being null when nobody said one; a quantity
 * without unit is a count and adds up with the pieces.
 *
 * @phpstan-type Need array{quantity: float, unit: ?string}
 */
final class Needs
{
    /** Below this, a quantity is nothing left to buy. */
    public const EPSILON = 0.0001;

    /** Each convertible unit with its base unit and its factor to it. */
    private const BASES = [
        'g' => ['g', 1.0],
        'kg' => ['g', 1000.0],
        'ml' => ['ml', 1.0],
        'cl' => ['ml', 10.0],
        'l' => ['ml', 1000.0],
    ];

    /**
     * The quantity in its base unit: 1.5 kg is 1500 g, 2 pieces stay 2 pieces.
     *
     * @return array{0: float, 1: ?Unit}
     */
    public static function toBase(float $quantity, ?Unit $unit): array
    {
        if (null === $unit || !isset(self::BASES[$unit->value])) {
            return [$quantity, $unit];
        }

        [$base, $factor] = self::BASES[$unit->value];

        return [$quantity * $factor, Unit::from($base)];
    }

    /**
     * A base quantity as a person reads it: 1500 g is 1.5 kg, 250 g stays 250 g.
     *
     * @return array{0: float, 1: ?Unit}
     */
    public static function readable(float $quantity, ?Unit $unit): array
    {
        if ($quantity >= 1000 && Unit::Gram === $unit) {
            return [$quantity / 1000, Unit::Kilogram];
        }

        if ($quantity >= 1000 && Unit::Milliliter === $unit) {
            return [$quantity / 1000, Unit::Liter];
        }

        return [$quantity, $unit];
    }

    /**
     * Adds a signed quantity to a list of needs, in the base unit. An entry
     * that falls to nothing is dropped.
     *
     * @param list<Need> $needs
     *
     * @return list<Need>
     */
    public static function add(array $needs, float $quantity, ?Unit $unit): array
    {
        [$quantity, $unit] = self::toBase($quantity, $unit);

        $merged = [];
        foreach ([...$needs, ['quantity' => $quantity, 'unit' => $unit?->value]] as $need) {
            [$q, $u] = self::toBase((float) $need['quantity'], null === $need['unit'] ? null : Unit::from($need['unit']));
            // « 4 tomates » says no unit: it is a count, so it adds to the pieces.
            $key = $u->value ?? Unit::Piece->value;

            if (isset($merged[$key])) {
                $merged[$key]['quantity'] += $q;
                $merged[$key]['unit'] ??= $u?->value;

                continue;
            }

            $merged[$key] = ['quantity' => $q, 'unit' => $u?->value];
        }

        return array_values(array_filter(
            $merged,
            static fn (array $need) => $need['quantity'] > self::EPSILON,
        ));
    }

    /**
     * The needs of a quantity written on a line, in base units.
     *
     * @return list<Need>
     */
    public static function of(?float $quantity, ?Unit $unit): array
    {
        return null === $quantity ? [] : self::add([], $quantity, $unit);
    }
}
