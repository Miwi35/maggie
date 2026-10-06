<?php

namespace Maggie\Grocery\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Grocery\Entity\Product;
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
}
