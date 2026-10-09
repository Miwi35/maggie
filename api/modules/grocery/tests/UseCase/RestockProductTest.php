<?php

namespace Maggie\Grocery\Tests\UseCase;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\UseCase\RestockProduct;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class RestockProductTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/update_stock.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function rice(): Product
    {
        return $this->em()->find(Product::class, $this->getFixture('rice')->getId());
    }

    private function restock(): ?GroceryList
    {
        return self::getContainer()->get(RestockProduct::class)->execute($this->rice());
    }

    /** @return list<GroceryItem> */
    private function lines(): array
    {
        $this->em()->clear();

        return array_values($this->em()->getRepository(GroceryItem::class)->findBy([], ['position' => 'ASC']));
    }

    private function seedLine(bool $checked): void
    {
        $list = $this->em()->getRepository(GroceryList::class)->findOrCreateForUser($this->em()->find(User::class, $this->getFixture('test_user')->getId()));
        $item = new GroceryItem();
        $item->setProduct($this->rice());
        $item->setSource(GroceryItemSource::Manual);
        $item->setChecked($checked);
        $item->setQuantity(1);
        $item->setUnit(Unit::Pack);
        $item->setPosition(1);
        $list->addItem($item);
        $this->em()->flush();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testTheRestockQuantityGoesOnTheListAndTheOpenScreensAreTold(): void
    {
        $list = $this->restock();

        self::assertNotNull($list);
        $lines = $this->lines();
        self::assertCount(1, $lines);
        self::assertSame(2.0, $lines[0]->getQuantity());
        self::assertSame(Unit::Pack, $lines[0]->getUnit());
        self::assertSame(GroceryItemSource::Restock, $lines[0]->getSource());
        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testAnOpenLineIsRaisedInsteadOfDuplicated(): void
    {
        $this->seedLine(false);

        $this->restock();

        $lines = $this->lines();
        self::assertCount(1, $lines);
        self::assertSame(3.0, $lines[0]->getQuantity());
    }

    public function testATickedLineIsNotMerged(): void
    {
        $this->seedLine(true);

        $this->restock();

        $lines = $this->lines();
        self::assertCount(2, $lines);
        self::assertSame(1.0, $lines[0]->getQuantity());
        self::assertSame(2.0, $lines[1]->getQuantity());
    }

    public function testNothingIsAddedWhenAutomaticRestockIsOff(): void
    {
        $this->rice()->setAutoRestock(false);
        $this->em()->flush();

        self::assertNull($this->restock());

        self::assertSame([], $this->lines());
        $this->assertMercureUpdateCount(0);
    }

    public function testNothingIsAddedWithoutARestockQuantity(): void
    {
        $this->rice()->setRestockQuantity(null);
        $this->em()->flush();

        self::assertNull($this->restock());

        self::assertSame([], $this->lines());
    }
}
