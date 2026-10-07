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
}
