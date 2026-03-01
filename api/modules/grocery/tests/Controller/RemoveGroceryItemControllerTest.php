<?php

namespace Maggie\Grocery\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class RemoveGroceryItemControllerTest extends WebTestCase
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
        $this->client->request('DELETE', '/api/grocery_items/fake-id');

        self::assertResponseStatusCodeSame(401);
    }

    public function testRemoveNonExistentItemReturns404(): void
    {
        $this->loadFixtures('grocery.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('DELETE', '/api/grocery_items/01JNBA2TQV38079SXMWMQP3D1F', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(404);
    }

    public function testRemoveItemDeletesAndPublishes(): void
    {
        $this->loadFixtures('grocery.yaml');
        $itemId = (string) $this->getFixture('item_tomato')->getId();
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('DELETE', "/api/grocery_items/$itemId", [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(GroceryItem::class, $itemId));

        // Remaining items should be 1 (item_milk)
        $items = $em->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $items);

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }
}
