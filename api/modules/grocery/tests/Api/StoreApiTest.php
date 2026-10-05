<?php

namespace Maggie\Grocery\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Grocery\Entity\Store;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class StoreApiTest extends WebTestCase
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
    private function patch(Store $entity, array $payload): void
    {
        $this->client->request('PATCH', '/api/stores/'.$entity->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    private function reload(Store $entity): Store
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Store::class)->find($entity->getId());
    }

    private function load(string $ref = 'store'): Store
    {
        $this->loadFixtures('StoreApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        return $this->getFixture($ref);
    }

    public function testPatchRequiresAuthentication(): void
    {
        $entity = $this->load();

        $this->client->request('PATCH', '/api/stores/'.$entity->getId(), [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
        ], json_encode(['description' => null], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchWithNullDescriptionClearsIt(): void
    {
        $store = $this->load();

        $this->patch($store, ['description' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($store);
        self::assertNull($reloaded->getDescription());
        self::assertSame('Supermarché', $reloaded->getName(), 'Fields left out of the payload are untouched');
        self::assertSame(4, $reloaded->getVisitOrder());
        $this->assertMercureUpdatePublished('/stores/');
        $this->assertElasticsearchIndexDispatched(Store::class);
    }

    public function testPatchSendingBackTheIdAsAnIriAsTheAdminDoesIsAccepted(): void
    {
        $store = $this->load();

        // react-admin's Hydra data provider replaces `id` by the IRI and adds `originId`.
        $this->patch($store, [
            'id' => '/api/stores/'.$store->getId(),
            'originId' => (string) $store->getId(),
            'description' => 'Open late',
        ]);

        self::assertResponseIsSuccessful();
        self::assertSame('Open late', $this->reload($store)->getDescription());
    }

    public function testPatchWithoutDescriptionKeepsIt(): void
    {
        $store = $this->load();

        $this->patch($store, ['name' => 'Hypermarché']);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($store);
        self::assertSame('Hypermarché', $reloaded->getName());
        self::assertSame('Supermarket for general groceries', $reloaded->getDescription());
    }
}
