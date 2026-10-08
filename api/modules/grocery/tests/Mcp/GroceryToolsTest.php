<?php

namespace Maggie\Grocery\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\ProductStockState;
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
    use SecurityTokenTrait;
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
        $this->loginFixtureUser();

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
        $this->loginFixtureUser();

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
        $this->loginFixtureUser();

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
        $this->loginFixtureUser();

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
        $this->loginFixtureUser();

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
        $this->loginFixtureUser();

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
        $this->loginFixtureUser();
        // Clear identity map so tool loads fresh data with relations from DB
        $this->em()->clear();

        $tool = self::getContainer()->get(GetGroceryListTool::class);
        $result = $tool();

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('groceryList', $data);
        self::assertSame(2, $data['groceryList']['totalItems']);
        self::assertSame(1, $data['groceryList']['checkedItems']);
    }

    /**
     * A line deferred by `buyAfter` is not today's shopping (MAG-101).
     *
     * The tool says so in its own description and the mobile client agrees, but
     * nothing pinned it — and `buyAfter` is not something the owner sets by
     * hand: `MealGrocerySync` puts it there from an ingredient's shelf life, so
     * a meal planned ten days out defers its perishables on its own. A regression
     * here reads as "the shopping list is full of things I cannot buy yet".
     *
     * The admin and the mobile app do the same and show the hidden lines in a
     * « Plus tard » section (MAG-120): the tool's `later` list is that section.
     */
    public function testGetGroceryListHidesALineDeferredToTheFuture(): void
    {
        $this->loadFixtures('grocery-deferred.yaml');
        $this->loginFixtureUser();
        $this->em()->clear();

        $tool = self::getContainer()->get(GetGroceryListTool::class);
        $data = json_decode($tool(), true, 512, JSON_THROW_ON_ERROR);

        $labels = $this->labelsOf($data);
        self::assertContains('Pain', $labels, 'a line with no date is today’s shopping');
        self::assertNotContains('Liquide vaisselle', $labels, 'a line deferred six days out is on today’s list');

        // Two lines, not one: the bread with no date, and the one whose date has
        // already passed. A deferral that never expired would be the same bug
        // the other way round.
        self::assertSame(2, $data['groceryList']['totalItems']);
        // Hidden, not lost — the agent can still say there is something coming.
        self::assertSame(1, $data['groceryList']['deferredCount']);
    }

    public function testGetGroceryListListsTheDeferredLinesInALaterSection(): void
    {
        $this->loadFixtures('grocery-deferred.yaml');
        $this->loginFixtureUser();
        $this->em()->clear();

        $tool = self::getContainer()->get(GetGroceryListTool::class);
        $data = json_decode($tool(), true, 512, JSON_THROW_ON_ERROR);

        $later = $data['groceryList']['later'] ?? null;
        self::assertIsArray($later, 'the hidden lines are only counted, never named');
        self::assertCount(1, $later);
        self::assertSame('Liquide vaisselle', $later[0]['label']);
        self::assertSame((new \DateTimeImmutable('+6 days'))->format('Y-m-d'), $later[0]['buyAfter']);
        self::assertNotContains('Liquide vaisselle', $this->labelsOf($data), 'it is in `later`, not in today’s groups');
    }

    public function testGetGroceryListHasNoLaterSectionWhenTheDeferredLinesAreAlreadyShown(): void
    {
        $this->loadFixtures('grocery-deferred.yaml');
        $this->loginFixtureUser();
        $this->em()->clear();

        $tool = self::getContainer()->get(GetGroceryListTool::class);
        $data = json_decode($tool(true), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame([], $data['groceryList']['later']);
    }

    public function testGetGroceryListShowsADeferredLineWhenAskedFor(): void
    {
        $this->loadFixtures('grocery-deferred.yaml');
        $this->loginFixtureUser();
        $this->em()->clear();

        $tool = self::getContainer()->get(GetGroceryListTool::class);
        $data = json_decode($tool(true), true, 512, JSON_THROW_ON_ERROR);

        self::assertContains('Liquide vaisselle', $this->labelsOf($data));
        self::assertSame(3, $data['groceryList']['totalItems']);
        self::assertSame(1, $data['groceryList']['deferredCount'], 'the count still says one is deferred');

        // And it carries the date, so the answer can say when.
        $deferred = null;
        foreach ($data['groceryList']['storeGroups'] as $group) {
            foreach ($group['items'] as $item) {
                if ('Liquide vaisselle' === $item['label']) {
                    $deferred = $item;
                }
            }
        }
        self::assertNotNull($deferred);
        self::assertSame(
            (new \DateTimeImmutable('+6 days'))->format('Y-m-d'),
            $deferred['buyAfter'] ?? null,
        );
    }

    /**
     * Every label the tool returned, whatever shop it grouped them under.
     *
     * @param array<string, mixed> $data
     *
     * @return list<string>
     */
    private function labelsOf(array $data): array
    {
        $labels = [];

        foreach ($data['groceryList']['storeGroups'] as $group) {
            foreach ($group['items'] as $item) {
                $labels[] = $item['label'];
            }
        }

        return $labels;
    }

    public function testCheckGroceryItemUpdatesAndPublishes(): void
    {
        $this->loadFixtures('grocery.yaml');
        $this->loginFixtureUser();
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
        $this->loginFixtureUser();
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
        $this->loginFixtureUser();
        $this->em()->clear();

        $tool = self::getContainer()->get(EndErrandTool::class);
        $result = $tool();

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertTrue($data['checkedRemoved']);
        // Only the unchecked item_tomato remains
        self::assertSame(1, $data['remainingCount']);
        self::assertSame([], $data['restockedProducts']);

        $this->em()->clear();
        $items = $this->em()->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertFalse($items[0]->isChecked());

        $this->assertMercureUpdatePublished('/grocery_lists/');
    }

    public function testEndErrandReturnsRestockedProducts(): void
    {
        $this->loadFixtures('grocery_restock.yaml');
        $this->loginFixtureUser();
        $riceId = (string) $this->getFixture('product_rice')->getId();
        $detergentId = (string) $this->getFixture('product_detergent')->getId();
        $this->em()->clear();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $tool = self::getContainer()->get(EndErrandTool::class);
        $data = json_decode($tool(), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame([['id' => $riceId, 'name' => 'Riz', 'stockState' => 'in_stock']], $data['restockedProducts']);
        self::assertSame(1, $data['remainingCount']);

        $this->em()->clear();
        self::assertSame(ProductStockState::InStock, $this->em()->find(Product::class, $riceId)->getStockState());
        self::assertSame(ProductStockState::Low, $this->em()->find(Product::class, $detergentId)->getStockState());

        $this->assertMercureUpdatePublished('/api/products/'.$riceId);
        $this->assertElasticsearchIndexDispatchedFor(Product::class, $riceId);
    }

    public function testMoveToFallbackReassignsStore(): void
    {
        $this->loadFixtures('grocery.yaml');
        $this->loginFixtureUser();
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
        $this->loginFixtureUser();
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
        $this->loginFixtureUser();
        $this->em()->clear();

        $tool = self::getContainer()->get(ReorderGroceryItemsTool::class);
        $result = $tool([
            ['id' => '01JNOTEXIST000000000000000', 'position' => 0],
        ]);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('error', $data);
    }
}
