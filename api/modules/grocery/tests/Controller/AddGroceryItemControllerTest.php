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

    public function testAddItemWithCategoryCreatesProductWithCategory(): void
    {
        $this->loadFixtures('user.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'Yaourt',
            'quantity' => 4,
            'unit' => 'piece',
            'category' => 'dairy',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $products = $em->getRepository(Product::class)->findAll();
        self::assertCount(1, $products);
        self::assertSame('dairy', $products[0]->getCategory()->value);

        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testAddItemUpdatesExistingProductCategory(): void
    {
        $this->loadFixtures('product.yaml');
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        // Bananes fixture is 'produce', update to 'other'
        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'Bananes',
            'quantity' => 1,
            'unit' => 'piece',
            'category' => 'other',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $products = $em->getRepository(Product::class)->findAll();
        self::assertCount(1, $products);
        self::assertSame('other', $products[0]->getCategory()->value);

        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testAddItemUpdatesExistingProductPreferredStore(): void
    {
        $this->loadFixtures('product.yaml');
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        // Bananes has preferredStore=supermarket, add with storeName to create a new store
        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'Bananes',
            'quantity' => 2,
            'unit' => 'piece',
            'storeName' => 'Primeur',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $products = $em->getRepository(Product::class)->findAll();
        self::assertCount(1, $products);
        self::assertSame('Primeur', $products[0]->getPreferredStore()->getName());

        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testAddItemWithoutCategoryLeavesExistingProductUnchanged(): void
    {
        $this->loadFixtures('product.yaml');
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        // Add without category — existing 'produce' product should remain 'produce'
        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'Bananes',
            'quantity' => 1,
            'unit' => 'piece',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $products = $em->getRepository(Product::class)->findAll();
        self::assertCount(1, $products);
        self::assertSame('produce', $products[0]->getCategory()->value);
        self::assertSame('Supermarché', $products[0]->getPreferredStore()->getName());

        // No Product ES index dispatched — product was not modified
        $sent = $this->getAsyncTransport()->getSent();
        $productIndexDispatched = false;
        foreach ($sent as $envelope) {
            $msg = $envelope->getMessage();
            if ($msg instanceof \Maggie\Core\Elasticsearch\Message\IndexDocumentCommand && Product::class === $msg->entityClass) {
                $productIndexDispatched = true;
            }
        }
        self::assertFalse($productIndexDispatched, 'Product index should not be dispatched when no changes made');
    }

    public function testAddItemWithSameCategoryDoesNotTriggerProductIndex(): void
    {
        $this->loadFixtures('product.yaml');
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        // Send same category as existing — should not trigger update
        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'Bananes',
            'quantity' => 1,
            'unit' => 'piece',
            'category' => 'produce',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $products = $em->getRepository(Product::class)->findAll();
        self::assertSame('produce', $products[0]->getCategory()->value);

        // No Product ES index dispatched
        $sent = $this->getAsyncTransport()->getSent();
        $productIndexDispatched = false;
        foreach ($sent as $envelope) {
            $msg = $envelope->getMessage();
            if ($msg instanceof \Maggie\Core\Elasticsearch\Message\IndexDocumentCommand && Product::class === $msg->entityClass) {
                $productIndexDispatched = true;
            }
        }
        self::assertFalse($productIndexDispatched, 'Product index should not be dispatched when category is unchanged');
    }

    public function testAddItemWithExistingProductStoreIdUpdatesPreferredStore(): void
    {
        $this->loadFixtures('product.yaml');
        $storeId = (string) $this->getFixture('supermarket')->getId();
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        // Bananes already has supermarket as preferred — storeId same should not trigger update
        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'label' => 'Bananes',
            'quantity' => 1,
            'unit' => 'piece',
            'storeId' => $storeId,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $products = $em->getRepository(Product::class)->findAll();
        self::assertSame('Supermarché', $products[0]->getPreferredStore()->getName());

        // No Product ES index dispatched — same store
        $sent = $this->getAsyncTransport()->getSent();
        $productIndexDispatched = false;
        foreach ($sent as $envelope) {
            $msg = $envelope->getMessage();
            if ($msg instanceof \Maggie\Core\Elasticsearch\Message\IndexDocumentCommand && Product::class === $msg->entityClass) {
                $productIndexDispatched = true;
            }
        }
        self::assertFalse($productIndexDispatched, 'Product index should not be dispatched when store is unchanged');
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

    /** @param array<string, mixed> $body */
    private function addItem(array $body): void
    {
        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode($body, JSON_THROW_ON_ERROR));
    }

    /** @return list<GroceryItem> */
    private function itemsOf(string $label): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return array_values(array_filter(
            $em->getRepository(GroceryItem::class)->findAll(),
            static fn (GroceryItem $item) => $item->getLabel() === $label,
        ));
    }

    private function loadMergeFixtures(): void
    {
        $this->loadFixtures('add_item_merge.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        // The fixtures set each line's list, not the list's lines: reload the list from the database.
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();
    }

    public function testAddingAProductAlreadyOnTheListRaisesItsQuantity(): void
    {
        $this->loadMergeFixtures();

        $this->addItem(['label' => 'Riz', 'quantity' => 1, 'unit' => 'pack']);

        self::assertResponseIsSuccessful();
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(4, $data['itemCount']);

        $rice = $this->itemsOf('Riz');
        self::assertCount(1, $rice);
        self::assertSame(2.0, $rice[0]->getQuantity());
        self::assertSame('pack', $rice[0]->getUnit()?->value);

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testAddingAPackagedProductWithoutQuantityAddsOnePackaging(): void
    {
        $this->loadMergeFixtures();

        $this->addItem(['label' => 'Riz']);

        self::assertResponseIsSuccessful();
        $rice = $this->itemsOf('Riz');
        self::assertCount(1, $rice);
        self::assertSame(2.0, $rice[0]->getQuantity());
        self::assertSame('pack', $rice[0]->getUnit()?->value);
    }

    public function testAddingAPackagedProductNotYetOnTheListCountsInPackagings(): void
    {
        $this->loadMergeFixtures();

        $this->addItem(['label' => 'Semoule']);

        self::assertResponseIsSuccessful();
        $semolina = $this->itemsOf('Semoule');
        self::assertCount(1, $semolina);
        self::assertSame(1.0, $semolina[0]->getQuantity());
        self::assertSame('jar', $semolina[0]->getUnit()?->value);
    }

    public function testATickedLineIsNeverMerged(): void
    {
        $this->loadMergeFixtures();

        $this->addItem(['label' => 'Lait', 'quantity' => 1, 'unit' => 'l']);

        self::assertResponseIsSuccessful();
        $milk = $this->itemsOf('Lait');
        self::assertCount(2, $milk);
        $ticked = array_values(array_filter($milk, static fn (GroceryItem $i) => $i->isChecked()));
        $fresh = array_values(array_filter($milk, static fn (GroceryItem $i) => !$i->isChecked()));
        self::assertCount(1, $ticked);
        self::assertSame(2.0, $ticked[0]->getQuantity());
        self::assertCount(1, $fresh);
        self::assertSame(1.0, $fresh[0]->getQuantity());
    }

    public function testAnotherUnitMakesANewLine(): void
    {
        $this->loadMergeFixtures();

        $this->addItem(['label' => 'Farine', 'quantity' => 1, 'unit' => 'pack']);
        self::assertResponseIsSuccessful();

        $this->addItem(['label' => 'Riz', 'quantity' => 300, 'unit' => 'g']);
        self::assertResponseIsSuccessful();

        $flour = $this->itemsOf('Farine');
        self::assertCount(2, $flour);
        $rice = $this->itemsOf('Riz');
        self::assertCount(2, $rice);
        $quantities = array_map(static fn (GroceryItem $i) => [$i->getUnit()?->value, $i->getQuantity()], $rice);
        sort($quantities);
        self::assertSame([['g', 300.0], ['pack', 1.0]], $quantities);
    }

    public function testMergingMatchesTheProductNameCaseInsensitively(): void
    {
        $this->loadMergeFixtures();

        $this->addItem(['label' => 'farine', 'quantity' => 250, 'unit' => 'g']);

        self::assertResponseIsSuccessful();
        $flour = $this->itemsOf('Farine');
        self::assertCount(1, $flour);
        self::assertSame(750.0, $flour[0]->getQuantity());
    }

    public function testAddingALineWithNoQuantityOnEitherSideChangesNothing(): void
    {
        $this->loadMergeFixtures();

        $this->addItem(['label' => 'Sel']);

        self::assertResponseIsSuccessful();
        $salt = $this->itemsOf('Sel');
        self::assertCount(1, $salt);
        self::assertNull($salt[0]->getQuantity());
    }

    public function testAMergedLineDeferredByAMealComesBackToToday(): void
    {
        $this->loadFixtures('add_item_merge.yaml');
        $this->getFixture('item_farine')->setBuyAfter(new \DateTimeImmutable('+5 days'));
        self::getContainer()->get('doctrine.orm.entity_manager')->flush();
        $this->authenticateAsUser($this->getFixture('test_user'));
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();

        $this->addItem(['label' => 'Farine', 'quantity' => 500, 'unit' => 'g']);

        self::assertResponseIsSuccessful();
        $flour = $this->itemsOf('Farine');
        self::assertCount(1, $flour);
        self::assertSame(1000.0, $flour[0]->getQuantity());
        self::assertNull($flour[0]->getBuyAfter());
    }

    public function testALineWithNoUnitOfAPackagedProductReadsAsItsPackaging(): void
    {
        $this->loadMergeFixtures();

        $this->addItem(['label' => 'Pâtes', 'quantity' => 1, 'unit' => 'pack']);

        self::assertResponseIsSuccessful();
        $pasta = $this->itemsOf('Pâtes');
        self::assertCount(1, $pasta);
        self::assertSame(3.0, $pasta[0]->getQuantity());
        self::assertSame('pack', $pasta[0]->getUnit()?->value);
    }
}
