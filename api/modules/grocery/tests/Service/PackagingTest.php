<?php

namespace Maggie\Grocery\Tests\Service;

use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Service\Packaging;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

class PackagingTest extends TestCase
{
    private function product(?Unit $unit, ?float $size = null, ?Unit $sizeUnit = null): Product
    {
        return (new Product())
            ->setPackagingUnit($unit)
            ->setPackagingSize($size)
            ->setPackagingSizeUnit($sizeUnit);
    }

    /** @return iterable<string, array{float, Unit, int}> */
    public static function riceIn500gPacks(): iterable
    {
        yield '300 g is one pack' => [300, Unit::Gram, 1];
        yield '500 g is exactly one pack' => [500, Unit::Gram, 1];
        yield '700 g is two packs' => [700, Unit::Gram, 2];
        yield '1 kg is two packs' => [1, Unit::Kilogram, 2];
        yield '1.2 kg is three packs' => [1.2, Unit::Kilogram, 3];
    }

    #[DataProvider('riceIn500gPacks')]
    public function testMassRoundsUpToWholePacks(float $quantity, Unit $unit, int $packs): void
    {
        $result = (new Packaging())->packagedQuantity($this->product(Unit::Pack, 500, Unit::Gram), $quantity, $unit);

        self::assertNotNull($result);
        self::assertSame($packs, $result->count);
        self::assertSame(Unit::Pack, $result->unit);
        self::assertTrue($result->converted);
    }

    /** @return iterable<string, array{float, Unit, int}> */
    public static function milkIn1lBottles(): iterable
    {
        yield '25 cl is one bottle' => [25, Unit::Centiliter, 1];
        yield '1500 ml is two bottles' => [1500, Unit::Milliliter, 2];
        yield '2 l is two bottles' => [2, Unit::Liter, 2];
        yield '70 cl is one bottle' => [70, Unit::Centiliter, 1];
    }

    #[DataProvider('milkIn1lBottles')]
    public function testVolumeConvertsBetweenMlClAndL(float $quantity, Unit $unit, int $bottles): void
    {
        $result = (new Packaging())->packagedQuantity($this->product(Unit::Bottle, 1, Unit::Liter), $quantity, $unit);

        self::assertSame($bottles, $result?->count);
        self::assertTrue($result->converted);
    }

    public function testFloatNoiseDoesNotAddAPackaging(): void
    {
        // 0.7 l in 10 cl bottles is 7 bottles, not 8 because 0.7 * 1000 / 100 is 7.000…1.
        $result = (new Packaging())->packagedQuantity($this->product(Unit::Bottle, 10, Unit::Centiliter), 0.7, Unit::Liter);

        self::assertSame(7, $result?->count);
    }

    public function testCountedUnitsOnlyMatchThemselves(): void
    {
        $eggs = $this->product(Unit::Pack, 6, Unit::Piece);

        $result = (new Packaging())->packagedQuantity($eggs, 8, Unit::Piece);

        self::assertSame(2, $result?->count);
        self::assertSame(Unit::Pack, $result->unit);
        self::assertTrue($result->converted);
    }

    public function testAQuantityAskedInThePackagingUnitIsKept(): void
    {
        $result = (new Packaging())->packagedQuantity($this->product(Unit::Jar), 2, Unit::Jar);

        self::assertSame(2, $result?->count);
        self::assertSame(Unit::Jar, $result->unit);
        self::assertTrue($result->converted);
    }

    public function testAJarOfUnknownContentAskedInGramsIsOneJarNotConverted(): void
    {
        $result = (new Packaging())->packagedQuantity($this->product(Unit::Jar), 200, Unit::Gram);

        self::assertNotNull($result);
        self::assertSame(1, $result->count);
        self::assertSame(Unit::Jar, $result->unit);
        self::assertFalse($result->converted);
    }

    /** @return iterable<string, array{float, Unit}> */
    public static function incompatibleUnits(): iterable
    {
        yield 'pieces against grams' => [3, Unit::Piece];
        yield 'litres against grams' => [1, Unit::Liter];
        yield 'a can against grams' => [2, Unit::Can];
    }

    #[DataProvider('incompatibleUnits')]
    public function testIncompatibleFamiliesGiveOnePackagingNotConverted(float $quantity, Unit $unit): void
    {
        $result = (new Packaging())->packagedQuantity($this->product(Unit::Pack, 500, Unit::Gram), $quantity, $unit);

        self::assertSame(1, $result?->count);
        self::assertSame(Unit::Pack, $result->unit);
        self::assertFalse($result->converted);
    }

    public function testCountedUnitsDoNotConvertIntoEachOther(): void
    {
        $result = (new Packaging())->packagedQuantity($this->product(Unit::Pack, 6, Unit::Piece), 2, Unit::Bunch);

        self::assertSame(1, $result?->count);
        self::assertFalse($result->converted);
    }

    public function testAProductWithoutPackagingGivesNull(): void
    {
        self::assertNull((new Packaging())->packagedQuantity($this->product(null), 300, Unit::Gram));
    }

    public function testALineCountsInPackagingsWhateverTheUnitItWasAskedIn(): void
    {
        $rice = $this->product(Unit::Pack, 500, Unit::Gram);
        $packaging = new Packaging();

        self::assertSame(1, $packaging->inPackagings($rice, 300, Unit::Gram));
        self::assertSame(2, $packaging->inPackagings($rice, 600, Unit::Gram));
        self::assertSame(3, $packaging->inPackagings($rice, 3, Unit::Pack));
        self::assertSame(3, $packaging->inPackagings($rice, 3, null), 'no unit reads as the packaging');
        self::assertSame(1, $packaging->inPackagings($rice, null, null), 'no quantity is one packaging');
        self::assertSame(1, $packaging->inPackagings($rice, 0.2, Unit::Pack), 'never below one packaging');
    }

    public function testAProductWithoutPackagingHasNoPackagingCount(): void
    {
        self::assertNull((new Packaging())->inPackagings($this->product(null), 300, Unit::Gram));
    }

    public function testSeveralRecipeQuantitiesAreRoundedUpOnceTogether(): void
    {
        $rice = $this->product(Unit::Pack, 500, Unit::Gram);

        // 300 g + 300 g is 600 g: two packs, not 1 + 1 by luck of the rounding, and 0.4 + 0.4 kg is one pack and a bit.
        self::assertSame(2, (new Packaging())->packagedTotal($rice, [[300, Unit::Gram], [300, Unit::Gram]]));
        self::assertSame(1, (new Packaging())->packagedTotal($rice, [[100, Unit::Gram], [150, Unit::Gram]]));
        self::assertSame(3, (new Packaging())->packagedTotal($rice, [[0.5, Unit::Kilogram], [200, Unit::Gram], [1, Unit::Pack]]));
        self::assertNull((new Packaging())->packagedTotal($this->product(null), [[300, Unit::Gram]]));
    }
}
