<?php

namespace Maggie\Grocery\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Mcp\Tool\MarkGroceryItemMissingTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** Maggie marks an article missing in the store, or cancels it (MAG-764). */
class MarkGroceryItemMissingToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('missing.yaml');
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em;
    }

    /** @return array<string, mixed> */
    private function call(string $itemId, bool $missing = true): array
    {
        $tool = self::getContainer()->get(MarkGroceryItemMissingTool::class);

        return json_decode($tool($itemId, $missing), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    public function testWithoutAUserItAnswersAnError(): void
    {
        $data = $this->call($this->id('item_tomato'));

        self::assertStringContainsString('No user bound', $data['error']);
        self::assertNull($this->em()->find(GroceryItem::class, $this->id('item_tomato'))->getMissingAt());
    }

    public function testAnotherUsersItemAnswersLikeAnUnknownOne(): void
    {
        $this->loginFixtureUser();

        $data = $this->call($this->id('other_item'));

        self::assertSame("Grocery item not found: {$this->id('other_item')}", $data['error']);
        self::assertNull($this->em()->find(GroceryItem::class, $this->id('other_item'))->getMissingAt());
        $this->assertMercureUpdateCount(0);
    }

    public function testACheckedItemCannotBeMissing(): void
    {
        $this->loginFixtureUser();

        $data = $this->call($this->id('item_checked'));

        self::assertArrayHasKey('error', $data);
        self::assertNull($this->em()->find(GroceryItem::class, $this->id('item_checked'))->getMissingAt());
        $this->assertMercureUpdateCount(0);
    }

    public function testMarkingMovesTheItemToTheFallbackStoreAndPublishes(): void
    {
        $this->loginFixtureUser();
        $this->em();

        $data = $this->call($this->id('item_tomato'));

        self::assertSame(['success' => true, 'missing' => true], $data);
        $item = $this->em()->find(GroceryItem::class, $this->id('item_tomato'));
        self::assertNotNull($item->getMissingAt());
        self::assertSame('Supermarché', $item->getMissingFromStore()?->getName());
        self::assertSame('Primeur', $item->getStore()?->getName());
        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testMissingFalseCancels(): void
    {
        $this->loginFixtureUser();
        $this->call($this->id('item_tomato'));
        $this->resetMercure();

        $data = $this->call($this->id('item_tomato'), false);

        self::assertSame(['success' => true, 'missing' => false], $data);
        $item = $this->em()->find(GroceryItem::class, $this->id('item_tomato'));
        self::assertNull($item->getMissingAt());
        self::assertNull($item->getMissingFromStore());
        self::assertSame('Supermarché', $item->getStore()?->getName());
        $this->assertMercureUpdatePublished('/grocery_lists/');
    }
}
