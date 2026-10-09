<?php

namespace Maggie\Grocery\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Enum\GroceryItemSource;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class ProductApiTest extends WebTestCase
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

    /** @param array<string, mixed> $payload */
    private function patch(Product $entity, array $payload): void
    {
        $this->client->request('PATCH', '/api/products/'.$entity->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    private function reload(Product $entity): Product
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Product::class)->find($entity->getId());
    }

    private function load(string $ref = 'product'): Product
    {
        $this->loadFixtures('ProductApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        return $this->getFixture($ref);
    }

    public function testPatchRequiresAuthentication(): void
    {
        $entity = $this->load();

        $this->client->request('PATCH', '/api/products/'.$entity->getId(), [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
        ], json_encode(['defaultUnit' => null], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchWithNullDefaultUnitClearsIt(): void
    {
        $product = $this->load();

        $this->patch($product, ['defaultUnit' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($product);
        self::assertNull($reloaded->getDefaultUnit());
        self::assertSame('Riz', $reloaded->getName(), 'Fields left out of the payload are untouched');
        self::assertSame('other', $reloaded->getCategory()->value);
        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testPatchSetsThePreferredStoreTheFallbackStoreAndTheShelfLife(): void
    {
        $product = $this->load();
        $halles = $this->getFixture('halles');
        $bio = $this->getFixture('bio');

        $this->patch($product, [
            'preferredStore' => '/api/stores/'.$halles->getId(),
            'fallbackStore' => '/api/stores/'.$bio->getId(),
            'shelfLifeDays' => 14,
        ]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($product);
        self::assertSame((string) $halles->getId(), (string) $reloaded->getPreferredStore()?->getId());
        self::assertSame((string) $bio->getId(), (string) $reloaded->getFallbackStore()?->getId());
        self::assertSame(14, $reloaded->getShelfLifeDays());
        self::assertSame('Riz', $reloaded->getName());
        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testPatchSendingBackTheIdAsAnIriAsTheAdminDoesSavesThePreferredStore(): void
    {
        $product = $this->load();
        $halles = $this->getFixture('halles');

        // react-admin's Hydra data provider replaces `id` by the IRI and adds `originId`.
        $this->patch($product, [
            'id' => '/api/products/'.$product->getId(),
            'originId' => (string) $product->getId(),
            'preferredStore' => '/api/stores/'.$halles->getId(),
        ]);

        self::assertResponseIsSuccessful();
        self::assertSame((string) $halles->getId(), (string) $this->reload($product)->getPreferredStore()?->getId());
    }

    public function testPatchWithNullPreferredStoreClearsItAndKeepsTheOthers(): void
    {
        $product = $this->load('product_with_stores');

        $this->patch($product, ['preferredStore' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($product);
        self::assertNull($reloaded->getPreferredStore());
        self::assertSame((string) $this->getFixture('bio')->getId(), (string) $reloaded->getFallbackStore()?->getId());
        self::assertSame(30, $reloaded->getShelfLifeDays());
    }

    public function testPatchWithoutStoresKeepsThem(): void
    {
        $product = $this->load('product_with_stores');

        $this->patch($product, ['name' => 'Câpres au sel']);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($product);
        self::assertSame('Câpres au sel', $reloaded->getName());
        self::assertSame((string) $this->getFixture('halles')->getId(), (string) $reloaded->getPreferredStore()?->getId());
    }

    public function testPatchRefusesAStoreOfAnotherUser(): void
    {
        $product = $this->load();

        $this->patch($product, ['preferredStore' => '/api/stores/'.$this->getFixture('other_user_store')->getId()]);

        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->reload($product)->getPreferredStore());
    }

    public function testPostCreatesAProductWithItsStores(): void
    {
        $this->load();
        $halles = $this->getFixture('halles');

        $this->client->request('POST', '/api/products', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'name' => 'Sel de Guérande',
            'category' => 'other',
            'preferredStore' => '/api/stores/'.$halles->getId(),
            'shelfLifeDays' => 365,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $created = $em->getRepository(Product::class)->findOneBy(['name' => 'Sel de Guérande']);
        self::assertSame((string) $halles->getId(), (string) $created?->getPreferredStore()?->getId());
        self::assertSame(365, $created?->getShelfLifeDays());
    }

    public function testPatchWithoutDefaultUnitKeepsIt(): void
    {
        $product = $this->load();

        $this->patch($product, ['name' => 'Riz basmati']);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($product);
        self::assertSame('Riz basmati', $reloaded->getName());
        self::assertSame('kg', $reloaded->getDefaultUnit()?->value);
    }

    public function testPatchSetsThePackaging(): void
    {
        $product = $this->load();

        $this->patch($product, ['packagingUnit' => 'pack', 'packagingSize' => 500, 'packagingSizeUnit' => 'g']);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($product);
        self::assertSame('pack', $reloaded->getPackagingUnit()?->value);
        self::assertSame(500.0, $reloaded->getPackagingSize());
        self::assertSame('g', $reloaded->getPackagingSizeUnit()?->value);
        self::assertSame('Riz', $reloaded->getName());
        $this->assertMercureUpdatePublished('/products/');
        $updates = $this->getMercureHub()->getUpdates();
        $data = json_decode(end($updates)->getData(), true);
        self::assertEquals(
            ['pack', 500, 'g'],
            [$data['packagingUnit'] ?? null, $data['packagingSize'] ?? null, $data['packagingSizeUnit'] ?? null],
            'A second tab sees the packaging arrive',
        );
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testPatchSetsAJarWithoutContent(): void
    {
        $product = $this->load();

        $this->patch($product, ['packagingUnit' => 'jar']);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($product);
        self::assertSame('jar', $reloaded->getPackagingUnit()?->value);
        self::assertNull($reloaded->getPackagingSize());
        self::assertNull($reloaded->getPackagingSizeUnit());
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function invalidPackagings(): iterable
    {
        yield 'a size without its unit' => [['packagingUnit' => 'pack', 'packagingSize' => 500]];
        yield 'a size unit without a size' => [['packagingUnit' => 'pack', 'packagingSizeUnit' => 'g']];
        yield 'a size of zero' => [['packagingUnit' => 'pack', 'packagingSize' => 0, 'packagingSizeUnit' => 'g']];
        yield 'a negative size' => [['packagingUnit' => 'pack', 'packagingSize' => -1, 'packagingSizeUnit' => 'g']];
        yield 'a size without a packaging unit' => [['packagingSize' => 500, 'packagingSizeUnit' => 'g']];
    }

    /** @param array<string, mixed> $payload */
    #[\PHPUnit\Framework\Attributes\DataProvider('invalidPackagings')]
    public function testPatchRefusesAPackagingThatCannotBeComputed(array $payload): void
    {
        $product = $this->load();

        $this->patch($product, $payload);

        self::assertResponseStatusCodeSame(400);
        $reloaded = $this->reload($product);
        self::assertNull($reloaded->getPackagingUnit());
        self::assertNull($reloaded->getPackagingSize());
        self::assertNull($reloaded->getPackagingSizeUnit());
    }

    public function testPatchWithNullPackagingClearsIt(): void
    {
        $product = $this->load('product_with_packaging');

        $this->patch($product, ['packagingUnit' => null, 'packagingSize' => null, 'packagingSizeUnit' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($product);
        self::assertNull($reloaded->getPackagingUnit());
        self::assertNull($reloaded->getPackagingSize());
        self::assertNull($reloaded->getPackagingSizeUnit());
        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testPatchRefusesToClearOnlyTheSizeUnit(): void
    {
        $product = $this->load('product_with_packaging');

        $this->patch($product, ['packagingSizeUnit' => null]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame('g', $this->reload($product)->getPackagingSizeUnit()?->value);
    }

    public function testPostCreatesAProductWithItsPackaging(): void
    {
        $this->load();

        $this->client->request('POST', '/api/products', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'name' => 'Pâtes',
            'category' => 'other',
            'packagingUnit' => 'pack',
            'packagingSize' => 1,
            'packagingSizeUnit' => 'kg',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $created = $em->getRepository(Product::class)->findOneBy(['name' => 'Pâtes']);
        self::assertSame('pack', $created?->getPackagingUnit()?->value);
        self::assertSame(1.0, $created?->getPackagingSize());
        self::assertSame('kg', $created?->getPackagingSizeUnit()?->value);
        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    public function testPostRefusesASizeWithoutItsUnit(): void
    {
        $this->load();

        $this->client->request('POST', '/api/products', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'name' => 'Pâtes',
            'category' => 'other',
            'packagingUnit' => 'pack',
            'packagingSize' => 500,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(400);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->getRepository(Product::class)->findOneBy(['name' => 'Pâtes']));
    }

    private function deleteProduct(Product $product, bool $authenticated = true): void
    {
        $this->client->request('DELETE', '/api/products/'.$product->getId(), [], [], $authenticated ? $this->authHeaders() : []);
    }

    public function testDeleteRequiresAuthentication(): void
    {
        $product = $this->load();

        $this->deleteProduct($product, authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testDeleteRemovesAProductNoListUses(): void
    {
        $product = $this->load();

        $this->deleteProduct($product);

        self::assertResponseStatusCodeSame(204);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Product::class, $product->getId()));
        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchDeleteDispatched();
    }

    public function testDeleteKeepsTheGroceryItemsAndRecurringItemsThatReferencedTheProduct(): void
    {
        $product = $this->load('product_in_lists');
        $fromProduct = (string) $this->getFixture('grocery_item_from_product')->getId();
        $withLabel = (string) $this->getFixture('grocery_item_with_label')->getId();
        $recurring = (string) $this->getFixture('recurring_from_product')->getId();

        $this->deleteProduct($product);

        self::assertResponseStatusCodeSame(204);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Product::class, $product->getId()));

        $item = $em->find(GroceryItem::class, $fromProduct);
        self::assertNotNull($item, 'The list item is kept');
        self::assertNull($item->getProduct());
        self::assertSame('Lentilles', $item->getLabel(), 'The item keeps the product name as its label');
        $labelled = $em->find(GroceryItem::class, $withLabel);
        self::assertNull($labelled->getProduct());
        self::assertSame('Lentilles corail', $labelled->getLabel(), 'A label the user chose is not overwritten');

        $recurringItem = $em->find(RecurringGroceryItem::class, $recurring);
        self::assertNotNull($recurringItem, 'The recurring item is kept');
        self::assertNull($recurringItem->getProduct());
        self::assertSame('Lentilles', $recurringItem->getLabel());

        $this->assertMercureUpdatePublished('/products/');
        $this->assertElasticsearchDeleteDispatched();
        $this->assertElasticsearchIndexDispatched(RecurringGroceryItem::class);
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testAProductCreatedWithoutAnythingIsInStock(): void
    {
        $this->load();

        $this->client->request('POST', '/api/products', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['name' => 'Sel', 'category' => 'other'], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('in_stock', $body['stockState']);
        self::assertNull($body['restockQuantity'] ?? null);
        self::assertFalse($body['autoRestock'], 'REST spells the flag autoRestock');
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $created = $em->getRepository(Product::class)->findOneBy(['name' => 'Sel']);
        self::assertSame('in_stock', $created?->getStockState()->value);
        self::assertFalse($created->isAutoRestock());
    }

    public function testPatchSetsTheStockAndPublishesItWithTheSameSpellingAsRest(): void
    {
        $product = $this->load();

        $this->patch($product, ['stockState' => 'out', 'restockQuantity' => 2, 'autoRestock' => true]);

        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(['out', 2, true], [$body['stockState'], $body['restockQuantity'], $body['autoRestock']]);
        self::assertArrayNotHasKey('isAutoRestock', $body);

        $reloaded = $this->reload($product);
        self::assertSame('out', $reloaded->getStockState()->value);
        self::assertSame(2, $reloaded->getRestockQuantity());
        self::assertTrue($reloaded->isAutoRestock());
        self::assertSame('Riz', $reloaded->getName(), 'Fields left out of the payload are untouched');

        $this->assertMercureUpdatePublished('/products/');
        $productUpdates = array_values(array_filter(
            $this->getMercureHub()->getUpdates(),
            static fn ($update) => str_contains(implode(' ', $update->getTopics()), '/products/'),
        ));
        $data = json_decode(end($productUpdates)->getData(), true);
        self::assertSame(['out', 2, true], [$data['stockState'] ?? null, $data['restockQuantity'] ?? null, $data['autoRestock'] ?? null]);
        self::assertArrayNotHasKey('isAutoRestock', $data, 'Mercure spells the flag like REST');
        $this->assertElasticsearchIndexDispatched(Product::class);
    }

    /** @return list<GroceryItem> */
    private function listItems(): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(GroceryItem::class)->findBy(['product' => $this->getFixture('product')->getId()]);
    }

    public function testPatchingAProductToOutRestocksItWhenAutomaticRestockIsOn(): void
    {
        $product = $this->load();

        $this->patch($product, ['stockState' => 'out', 'restockQuantity' => 2, 'autoRestock' => true]);

        self::assertResponseIsSuccessful();
        $items = $this->listItems();
        self::assertCount(1, $items);
        self::assertSame(2.0, $items[0]->getQuantity());
        self::assertSame(GroceryItemSource::Restock, $items[0]->getSource());
        self::assertFalse($items[0]->isChecked());
        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testSavingAProductAlreadyOutAddsNothingMore(): void
    {
        $product = $this->load();
        $this->patch($product, ['stockState' => 'out', 'restockQuantity' => 2, 'autoRestock' => true]);
        self::assertResponseIsSuccessful();

        $this->patch($product, ['stockState' => 'out', 'name' => 'Riz basmati']);

        self::assertResponseIsSuccessful();
        $items = $this->listItems();
        self::assertCount(1, $items);
        self::assertSame(2.0, $items[0]->getQuantity());
    }

    public function testGoingFromLowToOutDoesNotRestockTwice(): void
    {
        $product = $this->load();
        $this->patch($product, ['stockState' => 'low', 'restockQuantity' => 2, 'autoRestock' => true]);
        self::assertResponseIsSuccessful();

        $this->patch($product, ['stockState' => 'out']);

        self::assertResponseIsSuccessful();
        $items = $this->listItems();
        self::assertCount(1, $items);
        self::assertSame(2.0, $items[0]->getQuantity());
    }

    public function testGoingBackInStockThenOutRestocksAgain(): void
    {
        $product = $this->load();
        $this->patch($product, ['stockState' => 'out', 'restockQuantity' => 2, 'autoRestock' => true]);
        $this->patch($product, ['stockState' => 'in_stock']);
        $this->patch($product, ['stockState' => 'out']);

        self::assertResponseIsSuccessful();
        $items = $this->listItems();
        self::assertCount(1, $items, 'the unticked line is raised, not duplicated');
        self::assertSame(4.0, $items[0]->getQuantity());
    }

    public function testNothingIsAddedWithoutAutomaticRestock(): void
    {
        $product = $this->load();

        $this->patch($product, ['stockState' => 'out', 'restockQuantity' => 2, 'autoRestock' => false]);

        self::assertResponseIsSuccessful();
        self::assertSame([], $this->listItems());
    }

    public function testNothingIsAddedWithoutARestockQuantity(): void
    {
        $product = $this->load();

        $this->patch($product, ['stockState' => 'out', 'autoRestock' => true]);

        self::assertResponseIsSuccessful();
        self::assertSame([], $this->listItems());
    }

    public function testAProductCreatedOutIsRestockedByTheCreation(): void
    {
        $this->load();

        $this->client->request('POST', '/api/products', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'name' => 'Sel', 'category' => 'other', 'stockState' => 'out', 'restockQuantity' => 1, 'autoRestock' => true,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $items = $em->getRepository(GroceryItem::class)->findBy(['source' => GroceryItemSource::Restock]);
        self::assertCount(1, $items);
        self::assertSame(1.0, $items[0]->getQuantity());
        $this->assertMercureUpdatePublished('/grocery_lists/');
    }

    public function testAProductCreatedInStockIsNotRestocked(): void
    {
        $this->load();

        $this->client->request('POST', '/api/products', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'name' => 'Sel', 'category' => 'other', 'restockQuantity' => 1, 'autoRestock' => true,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame([], $em->getRepository(GroceryItem::class)->findBy(['source' => GroceryItemSource::Restock]));
    }

    public function testPatchWithoutStockKeepsIt(): void
    {
        $product = $this->load();
        $this->patch($product, ['stockState' => 'low', 'restockQuantity' => 3, 'autoRestock' => true]);
        self::assertResponseIsSuccessful();

        $this->patch($product, ['name' => 'Riz basmati']);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($product);
        self::assertSame(['low', 3, true], [$reloaded->getStockState()->value, $reloaded->getRestockQuantity(), $reloaded->isAutoRestock()]);
    }

    public function testPatchWithNullRestockQuantityClearsIt(): void
    {
        $product = $this->load();
        $this->patch($product, ['restockQuantity' => 2]);
        self::assertResponseIsSuccessful();

        $this->patch($product, ['restockQuantity' => null]);

        self::assertResponseIsSuccessful();
        self::assertNull($this->reload($product)->getRestockQuantity());
    }

    public function testPatchRefusesANegativeRestockQuantity(): void
    {
        $product = $this->load();

        $this->patch($product, ['restockQuantity' => -1]);

        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->reload($product)->getRestockQuantity());
    }

    public function testPatchRefusesAnUnknownStockState(): void
    {
        $product = $this->load();

        $this->patch($product, ['stockState' => 'plenty']);

        self::assertResponseStatusCodeSame(400);
        self::assertSame('in_stock', $this->reload($product)->getStockState()->value);
    }

    public function testPostRefusesANegativeRestockQuantity(): void
    {
        $this->load();

        $this->client->request('POST', '/api/products', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['name' => 'Sel', 'category' => 'other', 'restockQuantity' => -2], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(400);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->getRepository(Product::class)->findOneBy(['name' => 'Sel']));
    }
}
