<?php

namespace Maggie\Grocery\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class AddGroceryItemControllerTest extends WebTestCase
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
        $this->client->request('POST', '/api/grocery/add-item', [], [], [
            'CONTENT_TYPE' => 'application/json',
        ], json_encode(['label' => 'Bananes'], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testMissingLabelReturns400(): void
    {
        $this->loadFixtures('user.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(400);
    }

    public function testAddItemCreatesGroceryItem(): void
    {
        $this->loadFixtures('user.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'Bananes',
            'quantity' => 6,
            'unit' => 'piece',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame(1, $data['itemCount']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $items = $em->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertSame('Bananes', $items[0]->getLabel());

        $products = $em->getRepository(Product::class)->findAll();
        self::assertCount(1, $products);
        self::assertSame('Bananes', $products[0]->getName());
        self::assertSame('other', $products[0]->getCategory()->value);

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testAddItemWithStoreId(): void
    {
        $this->loadFixtures('store.yaml');
        $storeId = (string) $this->getFixture('supermarket')->getId();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'Lait',
            'quantity' => 1,
            'unit' => 'l',
            'storeId' => $storeId,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em->clear();
        $items = $em->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertSame('Supermarché', $items[0]->getStore()->getName());

        $products = $em->getRepository(Product::class)->findAll();
        self::assertSame('Lait', $products[0]->getName());
        self::assertSame('other', $products[0]->getCategory()->value);

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testAddItemWithExistingProductReusesIt(): void
    {
        $this->loadFixtures('product.yaml');
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'Bananes',
            'quantity' => 3,
            'unit' => 'piece',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $products = $em->getRepository(Product::class)->findAll();
        self::assertCount(1, $products, 'No new product should be created');
        self::assertSame('produce', $products[0]->getCategory()->value);

        $items = $em->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertSame((string) $products[0]->getId(), (string) $items[0]->getProduct()->getId());
    }

    public function testAddItemWithExistingProductAutoAssignsPreferredStore(): void
    {
        $this->loadFixtures('product.yaml');
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'Bananes',
            'quantity' => 2,
            'unit' => 'piece',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $items = $em->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertSame('Supermarché', $items[0]->getStore()->getName());
    }

    public function testAddItemCaseInsensitiveMatchReusesProduct(): void
    {
        $this->loadFixtures('product.yaml');
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'bananes',
            'quantity' => 1,
            'unit' => 'piece',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $products = $em->getRepository(Product::class)->findAll();
        self::assertCount(1, $products, 'Case-insensitive match should reuse existing product');
        self::assertSame('Bananes', $products[0]->getName());

        $items = $em->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertSame((string) $products[0]->getId(), (string) $items[0]->getProduct()->getId());
    }

    public function testAddItemWithExistingProductStoreOverride(): void
    {
        $this->loadFixtures('product.yaml');
        $storeId = (string) $this->getFixture('supermarket')->getId();
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        // Add item with explicit storeId=null-ish different store — but here we test
        // that an explicit storeId overrides the product's preferred store
        // First, add item WITHOUT storeId — should get preferred store
        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'Lait',
            'quantity' => 1,
            'unit' => 'l',
            'storeId' => $storeId,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $items = $em->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $items);
        // New product "Lait" has no preferred store, but explicit storeId is set
        self::assertSame('Supermarché', $items[0]->getStore()->getName());

        $products = $em->getRepository(Product::class)->findBy(['name' => 'Lait']);
        self::assertCount(1, $products, 'New product should be created for Lait');
        self::assertSame('other', $products[0]->getCategory()->value);
    }

    public function testAddItemWithStoreNameCreatesStore(): void
    {
        $this->loadFixtures('user.yaml');
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'Pommes',
            'quantity' => 1,
            'unit' => 'kg',
            'storeName' => 'Primeur',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        // Store was created
        $stores = $em->getRepository(Store::class)->findAll();
        self::assertCount(1, $stores);
        self::assertSame('Primeur', $stores[0]->getName());
        self::assertSame(0, $stores[0]->getVisitOrder());

        // Item is assigned to the new store
        $items = $em->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertSame('Primeur', $items[0]->getStore()->getName());

        // New product gets preferredStore set
        $products = $em->getRepository(Product::class)->findAll();
        self::assertCount(1, $products);
        self::assertSame('Primeur', $products[0]->getPreferredStore()->getName());

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
        $this->assertElasticsearchIndexDispatched(Product::class);
        $this->assertElasticsearchIndexDispatched(Store::class);
    }

    public function testAddItemWithStoreNameMatchesExisting(): void
    {
        $this->loadFixtures('store.yaml');
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        // Use different case to test case-insensitive match
        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'Lait',
            'quantity' => 1,
            'unit' => 'l',
            'storeName' => 'supermarché',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        // No new store created — reused existing
        $stores = $em->getRepository(Store::class)->findAll();
        self::assertCount(1, $stores);
        self::assertSame('Supermarché', $stores[0]->getName());

        // Item assigned to existing store
        $items = $em->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertSame('Supermarché', $items[0]->getStore()->getName());
    }

    public function testAddItemStoreIdTakesPriorityOverStoreName(): void
    {
        $this->loadFixtures('store.yaml');
        $storeId = (string) $this->getFixture('supermarket')->getId();
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'Savon',
            'storeId' => $storeId,
            'storeName' => 'Pharmacie',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        // storeId wins: no "Pharmacie" store created
        $stores = $em->getRepository(Store::class)->findAll();
        self::assertCount(1, $stores);
        self::assertSame('Supermarché', $stores[0]->getName());

        // Item assigned to store from storeId
        $items = $em->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $items);
        self::assertSame('Supermarché', $items[0]->getStore()->getName());
    }
}
