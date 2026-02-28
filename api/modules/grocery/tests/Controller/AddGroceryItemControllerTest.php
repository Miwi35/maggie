<?php

namespace Maggie\Grocery\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Grocery\Entity\GroceryItem;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class AddGroceryItemControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
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
        $this->authenticateAsTestUser();

        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(400);
    }

    public function testAddItemCreatesGroceryItem(): void
    {
        $this->authenticateAsTestUser();

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
    }
}
