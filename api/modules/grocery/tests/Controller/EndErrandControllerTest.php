<?php

namespace Maggie\Grocery\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\ProductStockState;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class EndErrandControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('POST', '/api/grocery/end-errand', [], [], [
            'CONTENT_TYPE' => 'application/json',
        ], json_encode([], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testInvalidStoreIdTypeReturns400(): void
    {
        $this->loadFixtures('end_errand.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('POST', '/api/grocery/end-errand', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode(['storeId' => 123], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(400);
    }

    public function testEndErrandForStoreRemovesOnlyCheckedItemsOfThatStore(): void
    {
        $this->loadFixtures('end_errand.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $lidlId = (string) $this->getFixture('store_lidl')->getId();
        $tomatoId = (string) $this->getFixture('item_tomato')->getId();
        $butterId = (string) $this->getFixture('item_butter')->getId();
        $milkId = (string) $this->getFixture('item_milk')->getId();
        $chocolateId = (string) $this->getFixture('item_chocolate')->getId();
        $breadId = (string) $this->getFixture('item_bread')->getId();

        $this->client->request('POST', '/api/grocery/end-errand', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode(['storeId' => $lidlId], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame(1, $data['remainingCount']);
        self::assertCount(1, $data['remainingItems']);
        self::assertSame($milkId, $data['remainingItems'][0]['id']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        // Lidl checked items deleted
        self::assertNull($em->find(GroceryItem::class, $tomatoId));
        self::assertNull($em->find(GroceryItem::class, $butterId));
        // Lidl unchecked kept
        self::assertNotNull($em->find(GroceryItem::class, $milkId));
        // Auchan items untouched (chocolate is checked but in a different store)
        self::assertNotNull($em->find(GroceryItem::class, $chocolateId));
        self::assertNotNull($em->find(GroceryItem::class, $breadId));

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testEndErrandWithoutStoreIdRemovesAllCheckedItems(): void
    {
        $this->loadFixtures('end_errand.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $tomatoId = (string) $this->getFixture('item_tomato')->getId();
        $butterId = (string) $this->getFixture('item_butter')->getId();
        $chocolateId = (string) $this->getFixture('item_chocolate')->getId();
        $milkId = (string) $this->getFixture('item_milk')->getId();
        $breadId = (string) $this->getFixture('item_bread')->getId();

        $this->client->request('POST', '/api/grocery/end-errand', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame(2, $data['remainingCount']);

        $remainingIds = array_column($data['remainingItems'], 'id');
        self::assertContains($milkId, $remainingIds);
        self::assertContains($breadId, $remainingIds);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        // All checked items gone
        self::assertNull($em->find(GroceryItem::class, $tomatoId));
        self::assertNull($em->find(GroceryItem::class, $butterId));
        self::assertNull($em->find(GroceryItem::class, $chocolateId));
        // Unchecked kept
        self::assertNotNull($em->find(GroceryItem::class, $milkId));
        self::assertNotNull($em->find(GroceryItem::class, $breadId));

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    /** @return array<string, mixed> */
    private function endErrand(array $body = []): array
    {
        $this->client->request('POST', '/api/grocery/end-errand', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode($body, JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function stateOf(string $productId): ProductStockState
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Product::class, $productId)->getStockState();
    }

    public function testCheckedLinesPutTheirProductsBackInStock(): void
    {
        $this->loadFixtures('end_errand_restock.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $riceId = (string) $this->getFixture('product_rice')->getId();
        $pastaId = (string) $this->getFixture('product_pasta')->getId();
        $flourId = (string) $this->getFixture('ingredient_flour')->getId();

        $data = $this->endErrand();

        // Sugar was already in stock: nothing changed, so nothing to announce.
        self::assertSame(3, $data['restockedCount']);
        $restocked = array_column($data['restockedProducts'], null, 'id');
        self::assertSame(['Riz', 'in_stock'], [$restocked[$riceId]['name'], $restocked[$riceId]['stockState']]);
        self::assertArrayHasKey($pastaId, $restocked);
        self::assertArrayHasKey($flourId, $restocked);

        self::assertSame(ProductStockState::InStock, $this->stateOf($riceId));
        self::assertSame(ProductStockState::InStock, $this->stateOf($pastaId));
        self::assertSame(ProductStockState::InStock, $this->stateOf($flourId));
    }

    public function testUncheckedLineLeavesItsProductState(): void
    {
        $this->loadFixtures('end_errand_restock.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $detergentId = (string) $this->getFixture('product_detergent')->getId();

        $data = $this->endErrand();

        self::assertNotContains($detergentId, array_column($data['restockedProducts'], 'id'));
        self::assertSame(ProductStockState::Low, $this->stateOf($detergentId));
    }

    public function testLineWithoutProductRestocksNothing(): void
    {
        $this->loadFixtures('end_errand.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $data = $this->endErrand();

        self::assertSame([], $data['restockedProducts']);
        self::assertSame(0, $data['restockedCount']);
    }

    public function testEndingOneStoreOnlyRestocksItsProducts(): void
    {
        $this->loadFixtures('end_errand_restock.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $riceId = (string) $this->getFixture('product_rice')->getId();
        $pastaId = (string) $this->getFixture('product_pasta')->getId();

        $data = $this->endErrand(['storeId' => (string) $this->getFixture('store_lidl')->getId()]);

        self::assertContains($riceId, array_column($data['restockedProducts'], 'id'));
        self::assertNotContains($pastaId, array_column($data['restockedProducts'], 'id'));
        self::assertSame(ProductStockState::InStock, $this->stateOf($riceId));
        self::assertSame(ProductStockState::Low, $this->stateOf($pastaId));
    }

    public function testEachRestockedProductIsPublishedAndReindexed(): void
    {
        $this->loadFixtures('end_errand_restock.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $riceId = (string) $this->getFixture('product_rice')->getId();
        $pastaId = (string) $this->getFixture('product_pasta')->getId();
        $flourId = (string) $this->getFixture('ingredient_flour')->getId();
        $detergentId = (string) $this->getFixture('product_detergent')->getId();

        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->endErrand();

        $topics = array_merge(...array_map(
            static fn ($update) => $update->getTopics(),
            $this->getMercureHub()->getUpdates(),
        ));
        foreach ([$riceId, $pastaId, $flourId] as $id) {
            self::assertContains(sprintf('/users/%s/api/products/%s', (string) $this->getFixture('test_user')->getId(), $id), $topics);
        }
        self::assertNotContains(sprintf('/users/%s/api/products/%s', (string) $this->getFixture('test_user')->getId(), $detergentId), $topics);

        $this->assertElasticsearchIndexDispatchedFor(Product::class, $riceId);
        $this->assertElasticsearchIndexDispatchedFor(Product::class, $pastaId);
        $this->assertElasticsearchIndexDispatchedFor(Ingredient::class, $flourId);
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }
}
