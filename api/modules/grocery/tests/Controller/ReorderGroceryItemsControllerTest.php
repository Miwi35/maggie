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

class ReorderGroceryItemsControllerTest extends WebTestCase
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
        $this->client->request('PATCH', '/api/grocery/reorder', [], [], [
            'CONTENT_TYPE' => 'application/json',
        ], json_encode(['items' => [['id' => 'abc', 'position' => 0]]], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testMissingItemsReturns400(): void
    {
        $this->loadFixtures('grocery.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('PATCH', '/api/grocery/reorder', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(400);
    }

    public function testInvalidItemStructureReturns400(): void
    {
        $this->loadFixtures('grocery.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('PATCH', '/api/grocery/reorder', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode(['items' => [['id' => 123, 'position' => 'abc']]], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(400);
    }

    public function testReorderUpdatesPositions(): void
    {
        $this->loadFixtures('grocery.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $tomatoId = (string) $this->getFixture('item_tomato')->getId();
        $milkId = (string) $this->getFixture('item_milk')->getId();

        $this->client->request('PATCH', '/api/grocery/reorder', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'items' => [
                ['id' => $tomatoId, 'position' => 10],
                ['id' => $milkId, 'position' => 5],
            ],
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        $tomato = $em->find(GroceryItem::class, $tomatoId);
        $milk = $em->find(GroceryItem::class, $milkId);
        self::assertSame(10, $tomato->getPosition());
        self::assertSame(5, $milk->getPosition());

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testReorderInvalidItemIdReturnsError(): void
    {
        $this->loadFixtures('grocery.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('PATCH', '/api/grocery/reorder', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'items' => [
                ['id' => '01JNOTEXIST000000000000000', 'position' => 0],
            ],
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(400);
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('error', $data);
    }
}
