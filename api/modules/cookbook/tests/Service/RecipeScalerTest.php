<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Service;

use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Service\RecipeScaler;
use Maggie\Grocery\Enum\Unit;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/** A recipe's quantities for a number of guests, as a cook reads them (MAG-482). */
class RecipeScalerTest extends TestCase
{
    public function testTheFactorIsTheGuestsOverTheServings(): void
    {
        $recipe = (new Recipe())->setServings(4);

        self::assertSame(1.5, RecipeScaler::factor($recipe, 6));
        self::assertSame(0.5, RecipeScaler::factor($recipe, 2));
        self::assertSame(2.0, RecipeScaler::factor($recipe, 8));
    }

    /** @return iterable<string, array{float, Unit, float, float}> */
    public static function quantities(): iterable
    {
        yield 'the ticket: 400 g for 6 of 4' => [400, Unit::Gram, 1.5, 600];
        yield 'the ticket: 400 g for 2 of 4' => [400, Unit::Gram, 0.5, 200];
        yield 'grams from 100 up go to 5' => [250, Unit::Gram, 0.75, 190];
        yield 'grams from 10 up go to the unit' => [25, Unit::Gram, 1 / 3, 8.3];
        yield 'grams under 10 go to the tenth' => [7, Unit::Gram, 1 / 3, 2.3];
        yield 'millilitres like grams' => [330, Unit::Milliliter, 1.5, 495];
        yield 'kilos to 0.05' => [1, Unit::Kilogram, 1 / 3, 0.35];
        yield 'pieces to the half' => [3, Unit::Piece, 1.5, 4.5];
        yield 'pieces round to the nearest half' => [1, Unit::Piece, 1 / 3, 0.5];
        yield 'never down to nothing' => [1, Unit::Piece, 0.1, 0.5];
        yield 'factor 1 leaves the quantity as written' => [333, Unit::Gram, 1.0, 333];
    }

    #[DataProvider('quantities')]
    public function testAQuantityIsScaledAndRoundedToItsUnit(float $quantity, Unit $unit, float $factor, float $expected): void
    {
        self::assertSame($expected, RecipeScaler::scale($quantity, $unit, $factor));
    }
}
