<?php

namespace Maggie\Grocery\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Mcp\Tool\ManageStoresTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class StoreToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testCreateStorePersistsAndPublishes(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageStoresTool::class);
        $result = $tool('create', name: 'Boulangerie', description: 'Bread shop', visitOrder: 3);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('Boulangerie', $data['store']['name']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $stores = $em->getRepository(Store::class)->findAll();
        self::assertCount(1, $stores);
        self::assertSame('Boulangerie', $stores[0]->getName());

        $this->assertMercureUpdatePublished('/stores/');
        $this->assertElasticsearchIndexDispatched(Store::class);
    }

    public function testListStoresReturnsAll(): void
    {
        $this->loadFixtures('store.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageStoresTool::class);
        $result = $tool('list');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(2, $data['stores']);
    }

    public function testUpdateStoreUpdatesAndPublishes(): void
    {
        $this->loadFixtures('store.yaml');
        $this->loginFixtureUser();

        $store = $this->getFixture('supermarket');

        $tool = self::getContainer()->get(ManageStoresTool::class);
        $result = $tool('update', storeId: (string) $store->getId(), name: 'Hypermarché');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('Hypermarché', $data['store']['name']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Store::class, $store->getId());
        self::assertSame('Hypermarché', $refreshed->getName());

        $this->assertMercureUpdatePublished('/stores/');
        $this->assertElasticsearchIndexDispatched(Store::class);
    }

    public function testDeleteStoreRemovesPublishesAndDeletes(): void
    {
        $this->loadFixtures('store.yaml');
        $this->loginFixtureUser();
        $this->loginUser($this->getFixture('test_user'));

        $store = $this->getFixture('supermarket');

        $tool = self::getContainer()->get(ManageStoresTool::class);
        $result = $tool('delete', storeId: (string) $store->getId());

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Store::class, $store->getId()));

        $this->assertMercureUpdatePublished('/stores/');
        $this->assertElasticsearchDeleteDispatched('stores');
    }

    public function testUpdateStoreClearEmptiesTheDescription(): void
    {
        $this->loadFixtures('store.yaml');
        $this->loginFixtureUser();

        $store = $this->getFixture('supermarket');

        $tool = self::getContainer()->get(ManageStoresTool::class);
        $data = json_decode($tool('update', storeId: (string) $store->getId(), clear: ['description', 'name', 'visitOrder']), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertNull($data['store']['description']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Store::class, $store->getId());
        self::assertNull($refreshed->getDescription());
        self::assertSame('Supermarché', $refreshed->getName(), 'Required fields cannot be cleared');
        self::assertSame(1, $refreshed->getVisitOrder());

        $this->assertMercureUpdatePublished('/stores/');
        $this->assertElasticsearchIndexDispatched(Store::class);
    }

    public function testUpdateStoreWithoutClearKeepsTheDescription(): void
    {
        $this->loadFixtures('store.yaml');
        $this->loginFixtureUser();

        $store = $this->getFixture('supermarket');

        $tool = self::getContainer()->get(ManageStoresTool::class);
        $data = json_decode($tool('update', storeId: (string) $store->getId(), visitOrder: 5), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame('Supermarket for general groceries', $data['store']['description']);
        self::assertSame(5, $data['store']['visitOrder']);
    }

    public function testUpdateUnknownStoreReturnsAnError(): void
    {
        $this->loadFixtures('store.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageStoresTool::class);
        $data = json_decode($tool('update', storeId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clear: ['description']), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }
}
