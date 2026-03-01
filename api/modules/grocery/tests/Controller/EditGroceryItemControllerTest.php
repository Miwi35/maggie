<?php

namespace Maggie\Grocery\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class EditGroceryItemControllerTest extends WebTestCase
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
        $this->client->request('PATCH', '/api/grocery/edit-item/01FAKE000000000000000000000', [], [], [
            'CONTENT_TYPE' => 'application/json',
        ], json_encode(['quantity' => 5], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testEmptyLabelReturns400(): void
    {
        $this->loadFixtures('grocery_with_product.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $itemId = (string) $this->getFixture('item_bananes')->getId();

        $this->client->request('PATCH', "/api/grocery/edit-item/$itemId", [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode(['label' => ''], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(400);
    }

    public function testUpdateQuantityAndUnit(): void
    {
        $this->loadFixtures('grocery_with_product.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $itemId = (string) $this->getFixture('item_bananes')->getId();

        $this->client->request('PATCH', "/api/grocery/edit-item/$itemId", [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'quantity' => 3,
            'unit' => 'kg',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $item = $em->getRepository(GroceryItem::class)->find($itemId);
        self::assertSame(3.0, $item->getQuantity());
        self::assertSame('kg', $item->getUnit()->value);

        // Product defaultUnit should also be updated
        $product = $item->getProduct();
        self::assertSame('kg', $product->getDefaultUnit()->value);

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testUpdateLabelOnProductItem(): void
    {
        $this->loadFixtures('grocery_with_product.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $itemId = (string) $this->getFixture('item_bananes')->getId();

        $this->client->request('PATCH', "/api/grocery/edit-item/$itemId", [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'Bananes plantain',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $item = $em->getRepository(GroceryItem::class)->find($itemId);
        self::assertSame('Bananes plantain', $item->getLabel());
        self::assertSame('Bananes plantain', $item->getProduct()->getName());

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertMercureUpdatePublished('/products/');
    }

    public function testUpdateLabelOnCustomLabelItemCreatesProduct(): void
    {
        $this->loadFixtures('grocery_with_product.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $itemId = (string) $this->getFixture('item_custom')->getId();

        $this->client->request('PATCH', "/api/grocery/edit-item/$itemId", [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'Savon de Marseille',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $item = $em->getRepository(GroceryItem::class)->find($itemId);
        self::assertNotNull($item->getProduct());
        self::assertSame('Savon de Marseille', $item->getProduct()->getName());
        self::assertNull($item->getCustomLabel(), 'customLabel should be cleared when product is linked');

        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testUpdateStoreViaStoreId(): void
    {
        $this->loadFixtures('grocery_with_product.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $itemId = (string) $this->getFixture('item_custom')->getId();
        $storeId = (string) $this->getFixture('supermarket')->getId();

        $this->client->request('PATCH', "/api/grocery/edit-item/$itemId", [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'storeId' => $storeId,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $item = $em->getRepository(GroceryItem::class)->find($itemId);
        self::assertSame('Supermarché', $item->getStore()->getName());

        $this->assertMercureUpdatePublished('/grocery_lists/');
    }

    public function testUpdateStoreViaStoreNameCreatesStore(): void
    {
        $this->loadFixtures('grocery_with_product.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $itemId = (string) $this->getFixture('item_bananes')->getId();

        $this->client->request('PATCH', "/api/grocery/edit-item/$itemId", [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'storeName' => 'Primeur',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        $stores = $em->getRepository(Store::class)->findAll();
        self::assertCount(2, $stores);

        $item = $em->getRepository(GroceryItem::class)->find($itemId);
        self::assertSame('Primeur', $item->getStore()->getName());

        // Product preferred store should be updated
        self::assertSame('Primeur', $item->getProduct()->getPreferredStore()->getName());

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(Store::class);
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testUpdateCategory(): void
    {
        $this->loadFixtures('grocery_with_product.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $itemId = (string) $this->getFixture('item_bananes')->getId();

        $this->client->request('PATCH', "/api/grocery/edit-item/$itemId", [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'category' => 'frozen',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $item = $em->getRepository(GroceryItem::class)->find($itemId);
        self::assertSame('frozen', $item->getProduct()->getCategory()->value);

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }
}
