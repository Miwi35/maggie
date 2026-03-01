<?php

namespace Maggie\Grocery\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Mcp\Tool\AddGroceryItemTool;
use Maggie\Grocery\Mcp\Tool\CheckGroceryItemTool;
use Maggie\Grocery\Mcp\Tool\EndErrandTool;
use Maggie\Grocery\Mcp\Tool\GetGroceryListTool;
use Maggie\Grocery\Mcp\Tool\MoveToFallbackTool;
use Maggie\Grocery\Mcp\Tool\RemoveGroceryItemTool;
use Maggie\Grocery\Mcp\Tool\ReorderGroceryItemsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class GroceryToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function em(): \Doctrine\ORM\EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    public function testAddGroceryItemPersistsAndPublishes(): void
    {
        $this->loadFixtures('user.yaml');

        $tool = self::getContainer()->get(AddGroceryItemTool::class);
        $result = $tool('Bananes', 6, 'piece');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame(1, $data['itemCount']);

        $this->em()->clear();
        $items = $this->em()->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertSame('Bananes', $items[0]->getLabel());

        $products = $this->em()->getRepository(Product::class)->findAll();
        self::assertCount(1, $products);
        self::assertSame('Bananes', $products[0]->getName());
        self::assertSame('other', $products[0]->getCategory()->value);

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testAddGroceryItemReusesExistingProduct(): void
    {
        $this->loadFixtures('product.yaml');

        $tool = self::getContainer()->get(AddGroceryItemTool::class);
        $result = $tool('Bananes', 3, 'piece');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $this->em()->clear();
        $products = $this->em()->getRepository(Product::class)->findAll();
        self::assertCount(1, $products, 'No new product should be created');
        self::assertSame('produce', $products[0]->getCategory()->value);

        $items = $this->em()->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertSame((string) $products[0]->getId(), (string) $items[0]->getProduct()->getId());
        self::assertSame('Supermarché', $items[0]->getStore()->getName());

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testAddGroceryItemCaseInsensitiveMatch(): void
    {
        $this->loadFixtures('product.yaml');

        $tool = self::getContainer()->get(AddGroceryItemTool::class);
        $result = $tool('bananes', 2, 'piece');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $this->em()->clear();
        $products = $this->em()->getRepository(Product::class)->findAll();
        self::assertCount(1, $products, 'Case-insensitive match should reuse existing product');
        self::assertSame('Bananes', $products[0]->getName());

        $items = $this->em()->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertSame((string) $products[0]->getId(), (string) $items[0]->getProduct()->getId());
    }

    public function testAddGroceryItemWithCategoryCreatesProductWithCategory(): void
    {
        $this->loadFixtures('user.yaml');

        $tool = self::getContainer()->get(AddGroceryItemTool::class);
        $result = $tool('Yaourt', 4, 'piece', null, null, 'dairy');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $this->em()->clear();
        $products = $this->em()->getRepository(Product::class)->findAll();
        self::assertCount(1, $products);
        self::assertSame('dairy', $products[0]->getCategory()->value);

        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testAddGroceryItemWithCategoryUpdatesExistingProduct(): void
    {
        $this->loadFixtures('product.yaml');

        $tool = self::getContainer()->get(AddGroceryItemTool::class);
        // Bananes fixture is 'produce', update to 'frozen'
        $result = $tool('Bananes', 1, 'piece', null, null, 'frozen');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $this->em()->clear();
        $products = $this->em()->getRepository(Product::class)->findAll();
        self::assertCount(1, $products, 'No new product should be created');
        self::assertSame('frozen', $products[0]->getCategory()->value);

        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testAddGroceryItemUpdatesExistingProductPreferredStore(): void
    {
        $this->loadFixtures('product.yaml');

        $tool = self::getContainer()->get(AddGroceryItemTool::class);
        // Bananes has preferredStore=supermarket, add with new storeName
        $result = $tool('Bananes', 2, 'piece', null, 'Primeur');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $this->em()->clear();
        $products = $this->em()->getRepository(Product::class)->findAll();
        self::assertCount(1, $products);
        self::assertSame('Primeur', $products[0]->getPreferredStore()->getName());

        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testGetGroceryListReturnsItems(): void
    {
        $this->loadFixtures('grocery.yaml');
        // Clear identity map so tool loads fresh data with relations from DB
        $this->em()->clear();

        $tool = self::getContainer()->get(GetGroceryListTool::class);
        $result = $tool();

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('groceryList', $data);
        self::assertSame(2, $data['groceryList']['totalItems']);
        self::assertSame(1, $data['groceryList']['checkedItems']);
    }

    public function testCheckGroceryItemUpdatesAndPublishes(): void
    {
        $this->loadFixtures('grocery.yaml');
        $itemId = (string) $this->getFixture('item_tomato')->getId();
        $this->em()->clear();

        $tool = self::getContainer()->get(CheckGroceryItemTool::class);
        $result = $tool($itemId, true);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertTrue($data['checked']);

        $this->em()->clear();
        $refreshed = $this->em()->find(GroceryItem::class, $itemId);
        self::assertTrue($refreshed->isChecked());

        $this->assertMercureUpdatePublished('/grocery_lists/');
    }

    public function testRemoveGroceryItemDeletesAndPublishes(): void
    {
        $this->loadFixtures('grocery.yaml');
        $itemId = (string) $this->getFixture('item_tomato')->getId();
        $this->em()->clear();

        $tool = self::getContainer()->get(RemoveGroceryItemTool::class);
        $result = $tool($itemId);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $this->em()->clear();
        self::assertNull($this->em()->find(GroceryItem::class, $itemId));

        $this->assertMercureUpdatePublished('/grocery_lists/');
    }

    public function testEndErrandRemovesCheckedItems(): void
    {
        $this->loadFixtures('grocery.yaml');
        $this->em()->clear();

        $tool = self::getContainer()->get(EndErrandTool::class);
        $result = $tool();

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertTrue($data['checkedRemoved']);
        // Only the unchecked item_tomato remains
        self::assertSame(1, $data['remainingCount']);

        $this->em()->clear();
        $items = $this->em()->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertFalse($items[0]->isChecked());

        $this->assertMercureUpdatePublished('/grocery_lists/');
    }

    public function testMoveToFallbackReassignsStore(): void
    {
        $this->loadFixtures('grocery.yaml');
        $supermarketId = (string) $this->getFixture('supermarket')->getId();
        $tomatoItemId = (string) $this->getFixture('item_tomato')->getId();
        $this->em()->clear();

        $tool = self::getContainer()->get(MoveToFallbackTool::class);
        $result = $tool($supermarketId);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $this->em()->clear();
        $item = $this->em()->find(GroceryItem::class, $tomatoItemId);
        // Tomato should now be assigned to the greengrocer (fallback store)
        self::assertSame('Primeur', $item->getStore()->getName());

        $this->assertMercureUpdatePublished('/grocery_lists/');
    }

    public function testReorderGroceryItemsUpdatesPositions(): void
    {
        $this->loadFixtures('grocery.yaml');
        $tomatoId = (string) $this->getFixture('item_tomato')->getId();
        $checkedId = (string) $this->getFixture('item_checked')->getId();
        $this->em()->clear();

        $tool = self::getContainer()->get(ReorderGroceryItemsTool::class);
        $result = $tool([
            ['id' => $tomatoId, 'position' => 20],
            ['id' => $checkedId, 'position' => 10],
        ]);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $this->em()->clear();
        $tomato = $this->em()->find(GroceryItem::class, $tomatoId);
        $checked = $this->em()->find(GroceryItem::class, $checkedId);
        self::assertSame(20, $tomato->getPosition());
        self::assertSame(10, $checked->getPosition());

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testReorderGroceryItemsInvalidIdReturnsError(): void
    {
        $this->loadFixtures('grocery.yaml');
        $this->em()->clear();

        $tool = self::getContainer()->get(ReorderGroceryItemsTool::class);
        $result = $tool([
            ['id' => '01JNOTEXIST000000000000000', 'position' => 0],
        ]);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('error', $data);
    }
}
