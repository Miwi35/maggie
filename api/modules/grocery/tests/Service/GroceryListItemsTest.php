<?php

namespace Maggie\Grocery\Tests\Service;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Service\GroceryListItems;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class GroceryListItemsTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/update_stock.yaml');
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function user(): User
    {
        return $this->em()->find(User::class, $this->getFixture('test_user')->getId());
    }

    private function rice(): Product
    {
        return $this->em()->find(Product::class, $this->getFixture('rice')->getId());
    }

    private function add(float|int $quantity, ?Unit $unit = Unit::Pack, GroceryItemSource $source = GroceryItemSource::Restock): GroceryList
    {
        return self::getContainer()->get(GroceryListItems::class)->addProduct($this->user(), $this->rice(), $quantity, $unit, $source);
    }

    /** @param array{checked?: bool, quantity?: float|null, unit?: Unit|null, position?: int} $line */
    private function seedLine(array $line): void
    {
        $list = $this->em()->getRepository(GroceryList::class)->findOrCreateForUser($this->user());
        $item = new GroceryItem();
        $item->setProduct($this->rice());
        $item->setSource(GroceryItemSource::Manual);
        $item->setChecked($line['checked'] ?? false);
        $item->setQuantity(array_key_exists('quantity', $line) ? $line['quantity'] : 1);
        $item->setUnit(array_key_exists('unit', $line) ? $line['unit'] : Unit::Pack);
        $item->setPosition($line['position'] ?? 1);
        $list->addItem($item);
        $this->em()->flush();
    }

    /** @return list<GroceryItem> */
    private function lines(): array
    {
        $this->em()->clear();

        return array_values($this->em()->getRepository(GroceryItem::class)->findBy([], ['position' => 'ASC']));
    }

    public function testAProductNotOnTheListGetsALineWithItsStoreAtTheEnd(): void
    {
        $list = $this->add(2);

        $lines = $this->lines();
        self::assertCount(1, $lines);
        self::assertSame(2.0, $lines[0]->getQuantity());
        self::assertSame(Unit::Pack, $lines[0]->getUnit());
        self::assertSame(GroceryItemSource::Restock, $lines[0]->getSource());
        self::assertSame('Supermarché', $lines[0]->getStore()->getName());
        self::assertSame($list->getId()->toRfc4122(), $lines[0]->getGroceryList()->getId()->toRfc4122());
    }

    public function testTheNewLineGoesAfterTheLastPosition(): void
    {
        $this->seedLine(['unit' => Unit::Kilogram, 'position' => 7]);

        $this->add(2);

        $lines = $this->lines();
        self::assertCount(2, $lines);
        self::assertSame(8, $lines[1]->getPosition());
    }

    public function testAnOpenLineOfTheSameProductAndUnitIsRaised(): void
    {
        $this->seedLine(['quantity' => 1.0]);

        $this->add(2, source: GroceryItemSource::Restock);

        $lines = $this->lines();
        self::assertCount(1, $lines);
        self::assertSame(3.0, $lines[0]->getQuantity());
        self::assertSame(GroceryItemSource::Manual, $lines[0]->getSource(), 'the line keeps its origin');
    }

    public function testAnOpenLineWithoutQuantityTakesTheNewOne(): void
    {
        $this->seedLine(['quantity' => null, 'unit' => null]);

        $this->add(2);

        $lines = $this->lines();
        self::assertCount(1, $lines);
        self::assertSame(2.0, $lines[0]->getQuantity());
        self::assertSame(Unit::Pack, $lines[0]->getUnit());
    }

    public function testATickedLineIsNotRaised(): void
    {
        $this->seedLine(['checked' => true]);

        $this->add(2);

        $lines = $this->lines();
        self::assertCount(2, $lines);
        self::assertSame([1.0, 2.0], [$lines[0]->getQuantity(), $lines[1]->getQuantity()]);
        self::assertTrue($lines[0]->isChecked());
        self::assertFalse($lines[1]->isChecked());
    }

    public function testALineInAnotherUnitIsNotRaised(): void
    {
        $this->seedLine(['quantity' => 1.0, 'unit' => Unit::Kilogram]);

        $this->add(2);

        $lines = $this->lines();
        self::assertCount(2, $lines);
        self::assertSame(Unit::Kilogram, $lines[0]->getUnit());
        self::assertSame(Unit::Pack, $lines[1]->getUnit());
    }
}
