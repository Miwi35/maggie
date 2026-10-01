<?php

namespace Maggie\Grocery\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryItem;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * Two users share the database: writes on an item id must never cross the user boundary.
 */
class GroceryItemIsolationControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;
    private string $ownItemId;
    private string $otherItemId;
    private string $otherStoreId;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('isolation.yaml');
        $this->ownItemId = (string) $this->getFixture('own_item')->getId();
        $this->otherItemId = (string) $this->getFixture('other_item')->getId();
        $this->otherStoreId = (string) $this->getFixture('other_store')->getId();
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em;
    }

    /** @param array<string, mixed>|null $body */
    private function call(string $method, string $uri, ?array $body = null): void
    {
        $this->client->request($method, $uri, [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR));
    }

    public function testCheckingAnotherUsersItemReturns404AndChangesNothing(): void
    {
        $this->call('PATCH', "/api/grocery_items/{$this->otherItemId}", ['checked' => true]);

        self::assertResponseStatusCodeSame(404);
        self::assertFalse($this->em()->find(GroceryItem::class, $this->otherItemId)->isChecked());
    }

    public function testRemovingAnotherUsersItemReturns404AndKeepsIt(): void
    {
        $this->call('DELETE', "/api/grocery_items/{$this->otherItemId}");

        self::assertResponseStatusCodeSame(404);
        self::assertNotNull($this->em()->find(GroceryItem::class, $this->otherItemId));
    }

    public function testRefusingAForeignItemGivesTheSameAnswerAsAnUnknownId(): void
    {
        $unknownId = '01JNBA2TQV38079SXMWMQP3D1F';

        $this->call('DELETE', "/api/grocery_items/{$this->otherItemId}");
        $foreign = str_replace($this->otherItemId, '<id>', (string) $this->client->getResponse()->getContent());

        $this->call('DELETE', "/api/grocery_items/$unknownId");
        $unknown = str_replace($unknownId, '<id>', (string) $this->client->getResponse()->getContent());

        self::assertSame($unknown, $foreign, 'Nothing may confirm that the id exists');
    }

    public function testReorderingAnotherUsersItemIsRefusedAndMovesNothing(): void
    {
        $this->call('PATCH', '/api/grocery/reorder', ['items' => [
            ['id' => $this->ownItemId, 'position' => 5],
            ['id' => $this->otherItemId, 'position' => 0],
        ]]);

        self::assertResponseStatusCodeSame(400);
        $em = $this->em();
        self::assertSame(7, $em->find(GroceryItem::class, $this->otherItemId)->getPosition());
        self::assertSame(1, $em->find(GroceryItem::class, $this->ownItemId)->getPosition(), 'A refused reorder is all-or-nothing');
    }

    public function testEditingAnItemWithAnotherUsersStoreLeavesTheStoreUnset(): void
    {
        $this->call('PATCH', "/api/grocery/edit-item/{$this->ownItemId}", ['storeId' => $this->otherStoreId]);

        self::assertNull($this->em()->find(GroceryItem::class, $this->ownItemId)->getStore());
    }

    public function testOwnerKeepsCheckingRemovingAndReordering(): void
    {
        $this->call('PATCH', "/api/grocery_items/{$this->ownItemId}", ['checked' => true]);
        self::assertResponseIsSuccessful();
        self::assertTrue($this->em()->find(GroceryItem::class, $this->ownItemId)->isChecked());

        $this->call('PATCH', '/api/grocery/reorder', ['items' => [['id' => $this->ownItemId, 'position' => 3]]]);
        self::assertResponseIsSuccessful();
        self::assertSame(3, $this->em()->find(GroceryItem::class, $this->ownItemId)->getPosition());

        $this->call('DELETE', "/api/grocery_items/{$this->ownItemId}");
        self::assertResponseIsSuccessful();
        self::assertNull($this->em()->find(GroceryItem::class, $this->ownItemId));
    }
}
