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
