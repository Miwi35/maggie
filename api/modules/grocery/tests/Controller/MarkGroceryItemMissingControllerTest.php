<?php

namespace Maggie\Grocery\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * An article missing in the store moves to its product's fallback store, and
 * back when the owner cancels (MAG-764).
 */
class MarkGroceryItemMissingControllerTest extends WebTestCase
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

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em;
    }

    private function login(): void
    {
        $this->loadFixtures('missing.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** @return array<string, mixed> */
    private function call(string $method, string $itemId): array
    {
        $this->client->request($method, "/api/grocery_items/$itemId/missing", [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ));

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('POST', '/api/grocery_items/fake-id/missing');

        self::assertResponseStatusCodeSame(401);
    }

    public function testAnUnknownItemReturns400(): void
    {
        $this->login();

        $this->call('POST', 'not-a-ulid');

        self::assertResponseStatusCodeSame(400);
    }

    public function testAnotherUsersItemReturns400AndChangesNothing(): void
    {
        $this->login();
        $otherItemId = $this->id('other_item');

        $this->call('POST', $otherItemId);

        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->em()->find(GroceryItem::class, $otherItemId)->getMissingAt());
        $this->assertMercureUpdateCount(0);
    }

    public function testACheckedItemCannotBeMissing(): void
    {
        $this->login();
        $itemId = $this->id('item_checked');

        $this->call('POST', $itemId);

        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->em()->find(GroceryItem::class, $itemId)->getMissingAt());
        $this->assertMercureUpdateCount(0);
    }

    public function testMarkingMovesTheItemToTheFallbackStoreAndPublishes(): void
    {
        $this->login();
        $itemId = $this->id('item_tomato');
        $supermarketId = $this->id('supermarket');
        $greengrocerId = $this->id('greengrocer');

        $data = $this->call('POST', $itemId);

        self::assertResponseIsSuccessful();
        self::assertSame(['success' => true, 'missing' => true, 'store' => $greengrocerId, 'missingFromStore' => $supermarketId], $data);

        $item = $this->em()->find(GroceryItem::class, $itemId);
        self::assertNotNull($item->getMissingAt());
        self::assertSame($supermarketId, (string) $item->getMissingFromStore()?->getId());
        self::assertSame($greengrocerId, (string) $item->getStore()?->getId());

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testWithoutAFallbackTheItemStaysInItsStore(): void
    {
        $this->login();
        $itemId = $this->id('item_rice');
        $supermarketId = $this->id('supermarket');

        $data = $this->call('POST', $itemId);

        self::assertResponseIsSuccessful();
        self::assertSame($supermarketId, $data['store']);

        $item = $this->em()->find(GroceryItem::class, $itemId);
        self::assertNotNull($item->getMissingAt());
        self::assertSame($supermarketId, (string) $item->getMissingFromStore()?->getId());
        self::assertSame($supermarketId, (string) $item->getStore()?->getId());
        $this->assertMercureUpdatePublished('/grocery_lists/');
    }

    public function testMarkingTwiceKeepsTheFirstMark(): void
    {
        $this->login();
        $itemId = $this->id('item_tomato');
        $this->call('POST', $itemId);
        $firstMark = $this->em()->find(GroceryItem::class, $itemId)->getMissingAt();

        $data = $this->call('POST', $itemId);

        self::assertResponseIsSuccessful();
        self::assertTrue($data['missing']);
        self::assertSame($this->id('supermarket'), $data['missingFromStore']);
        $item = $this->em()->find(GroceryItem::class, $itemId);
        self::assertEquals($firstMark, $item->getMissingAt());
        self::assertSame($this->id('supermarket'), (string) $item->getMissingFromStore()?->getId());
        self::assertSame($this->id('greengrocer'), (string) $item->getStore()?->getId());
    }

    public function testCancellingPutsTheItemBackInTheStoreItWasMissingFrom(): void
    {
        $this->login();
        $itemId = $this->id('item_tomato');
        $this->call('POST', $itemId);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->call('DELETE', $itemId);

        self::assertResponseIsSuccessful();
        self::assertSame(['success' => true, 'missing' => false, 'store' => $this->id('supermarket'), 'missingFromStore' => null], $data);

        $item = $this->em()->find(GroceryItem::class, $itemId);
        self::assertNull($item->getMissingAt());
        self::assertNull($item->getMissingFromStore());
        self::assertSame($this->id('supermarket'), (string) $item->getStore()?->getId());

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testCancellingAnItemThatIsNotMissingChangesNothing(): void
    {
        $this->login();
        $itemId = $this->id('item_rice');

        $data = $this->call('DELETE', $itemId);

        self::assertResponseIsSuccessful();
        self::assertFalse($data['missing']);
        self::assertSame($this->id('supermarket'), (string) $this->em()->find(GroceryItem::class, $itemId)->getStore()?->getId());
    }

    public function testTheListReadsTheMissingMarkAndTheStoreItWasMissingFrom(): void
    {
        $this->login();
        $itemId = $this->id('item_tomato');
        $this->call('POST', $itemId);
        $this->em();

        $this->client->request('GET', '/api/grocery_lists/'.$this->id('grocery_list'), [], [], array_merge(
            ['HTTP_ACCEPT' => 'application/ld+json'],
            $this->authHeaders(),
        ));
        self::assertResponseIsSuccessful();
        $list = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);

        $byId = array_column($list['items'], null, 'id');
        $item = $byId[$itemId];
        self::assertNotNull($item['missingAt']);
        // Embedded like `store`, so the mobile reads both with one Store DTO.
        self::assertSame($this->id('supermarket'), $item['missingFromStore']['id']);
        self::assertSame('Supermarché', $item['missingFromStore']['name']);
        self::assertSame($this->id('greengrocer'), $item['store']['id']);
        // API Platform skips null values: a line not missing has neither key.
        self::assertArrayNotHasKey('missingAt', $byId[$this->id('item_rice')]);
        self::assertArrayNotHasKey('missingFromStore', $byId[$this->id('item_rice')]);
    }
}
