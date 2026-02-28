<?php

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Cookbook\Entity\GroceryItem;
use Maggie\Cookbook\Entity\GroceryList;
use Maggie\Cookbook\Mcp\Tool\AddGroceryItemTool;
use Maggie\Cookbook\Mcp\Tool\CheckGroceryItemTool;
use Maggie\Cookbook\Mcp\Tool\EndErrandTool;
use Maggie\Cookbook\Mcp\Tool\GetGroceryListTool;
use Maggie\Cookbook\Mcp\Tool\MoveToFallbackTool;
use Maggie\Cookbook\Mcp\Tool\RemoveGroceryItemTool;
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

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
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
}
