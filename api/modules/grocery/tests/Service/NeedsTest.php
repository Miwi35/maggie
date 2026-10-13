<?php

declare(strict_types=1);

namespace Maggie\Grocery\Tests\Service;

use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Service\GroceryLineNeeds;
use Maggie\Grocery\Service\Needs;
use Maggie\Grocery\Service\Packaging;
use PHPUnit\Framework\TestCase;

class NeedsTest extends TestCase
{
    public function testMassAndVolumeAreAddedInTheirBaseUnit(): void
    {
        $needs = Needs::add(Needs::of(300, Unit::Gram), 0.5, Unit::Kilogram);

        self::assertSame([['quantity' => 800.0, 'unit' => 'g']], $needs);
        self::assertSame([['quantity' => 750.0, 'unit' => 'ml']], Needs::add(Needs::of(50, Unit::Centiliter), 25, Unit::Centiliter));
    }

    public function testUnitsThatCannotBeAddedKeepTheirOwnEntry(): void
    {
        $needs = Needs::add(Needs::of(300, Unit::Gram), 2, Unit::Piece);

        self::assertSame([['quantity' => 300.0, 'unit' => 'g'], ['quantity' => 2.0, 'unit' => 'piece']], $needs);
    }

    public function testAnEntryThatFallsToNothingIsDropped(): void
    {
        $needs = Needs::add(Needs::of(300, Unit::Gram), -300, Unit::Gram);

        self::assertSame([], $needs);
    }

    public function testABaseQuantityReadsInTheBiggerUnitFromAThousand(): void
    {
        self::assertSame([1.5, Unit::Kilogram], Needs::readable(1500, Unit::Gram));
        self::assertSame([250.0, Unit::Gram], Needs::readable(250, Unit::Gram));
        self::assertSame([2.0, Unit::Liter], Needs::readable(2000, Unit::Milliliter));
    }

    public function testNoQuantityIsNoNeed(): void
    {
        self::assertSame([], Needs::of(null, Unit::Gram));
    }

    public function testAProductWithoutPackagingReadsItsNeed(): void
    {
        $item = $this->item($this->product(null));
        $lineNeeds = new GroceryLineNeeds(new Packaging());

        $lineNeeds->apply($item, 300, Unit::Gram);
        $lineNeeds->apply($item, 500, Unit::Gram);

        self::assertSame(800.0, $item->getQuantity());
        self::assertSame(Unit::Gram, $item->getUnit());

        $lineNeeds->apply($item, 700, Unit::Gram);

        self::assertSame(1.5, $item->getQuantity());
        self::assertSame(Unit::Kilogram, $item->getUnit());

        $lineNeeds->apply($item, -1500, Unit::Gram);

        self::assertNull($item->getQuantity());
        self::assertNull($item->getNeeded());
    }

    public function testAPackagedProductReadsPackagingsCountedOnTheTotal(): void
    {
        $item = $this->item($this->product(Unit::Pack, 500, Unit::Gram));
        $lineNeeds = new GroceryLineNeeds(new Packaging());

        $lineNeeds->apply($item, 300, Unit::Gram);
        self::assertSame(1.0, $item->getQuantity());

        $lineNeeds->apply($item, 300, Unit::Gram);
        self::assertSame(2.0, $item->getQuantity());
        self::assertSame(Unit::Pack, $item->getUnit());

        $lineNeeds->apply($item, -300, Unit::Gram);
        self::assertSame(1.0, $item->getQuantity());

        $lineNeeds->apply($item, -300, Unit::Gram);
        self::assertNull($item->getQuantity());
    }

    public function testPackagingsAddedByHandMoveWithTheNeedsAndAreNotLostToThem(): void
    {
        $item = $this->item($this->product(Unit::Pack, 500, Unit::Gram));
        $lineNeeds = new GroceryLineNeeds(new Packaging());

        $lineNeeds->apply($item, 300, Unit::Gram);
        $item->setQuantity(3.0);

        // The shopper took two more packs; a further meal moves the line by what its need adds.
        $lineNeeds->apply($item, 300, Unit::Gram);
        self::assertSame(4.0, $item->getQuantity());

        $lineNeeds->apply($item, -600, Unit::Gram);
        self::assertSame(2.0, $item->getQuantity());
    }

    private function product(?Unit $unit, ?float $size = null, ?Unit $sizeUnit = null): Product
    {
        return (new Product())->setPackagingUnit($unit)->setPackagingSize($size)->setPackagingSizeUnit($sizeUnit);
    }

    private function item(Product $product): GroceryItem
    {
        return (new GroceryItem())->setProduct($product);
    }
}
