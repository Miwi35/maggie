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

class CheckGroceryItemControllerTest extends WebTestCase
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
        $this->client->request('PATCH', '/api/grocery_items/fake-id', [], [], [
            'CONTENT_TYPE' => 'application/json',
        ], json_encode(['checked' => true], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testMissingCheckedFieldReturns400(): void
    {
        $this->loadFixtures('grocery.yaml');
        $itemId = (string) $this->getFixture('item_tomato')->getId();
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('PATCH', "/api/grocery_items/$itemId", [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(400);
    }

    public function testCheckItemUpdatesAndPublishes(): void
    {
        $this->loadFixtures('grocery.yaml');
        $itemId = (string) $this->getFixture('item_tomato')->getId();
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('PATCH', "/api/grocery_items/$itemId", [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode(['checked' => true], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertTrue($data['checked']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $item = $em->find(GroceryItem::class, $itemId);
        self::assertTrue($item->isChecked());

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testUncheckItemUpdatesAndPublishes(): void
    {
        $this->loadFixtures('grocery.yaml');
        $itemId = (string) $this->getFixture('item_milk')->getId();
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('PATCH', "/api/grocery_items/$itemId", [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode(['checked' => false], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertFalse($data['checked']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $item = $em->find(GroceryItem::class, $itemId);
        self::assertFalse($item->isChecked());

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }
}
