<?php

namespace Maggie\Grocery\Tests\Projection;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Message\AddGroceryItemCommand;
use Maggie\Grocery\Message\DeleteProductCommand;
use Maggie\Grocery\Message\DeleteStoreCommand;
use Maggie\Grocery\Message\EditGroceryItemCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * What a grocery command changes reaches the open screens and the index
 * whole, not only the entity its handler returns (MAG-371).
 */
class GroceryProjectionTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/grocery.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function dispatch(object $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);

        return $em;
    }

    public function testAddingALineWithANewProductAndANewStorePublishesAndIndexesAllThree(): void
    {
        $userId = (string) $this->getFixture('test_user')->getId();

        $this->dispatch(new AddGroceryItemCommand(userId: $userId, label: 'Pâtes', storeName: 'Marché du jeudi'));

        $em = $this->em();
        $em->clear();
        $product = $em->getRepository(Product::class)->findOneBy(['name' => 'Pâtes']);
        $store = $em->getRepository(Store::class)->findOneBy(['name' => 'Marché du jeudi']);
        $list = $em->getRepository(GroceryList::class)->findOneBy(['user' => $userId]);
        self::assertNotNull($product);
        self::assertNotNull($store);

        $this->assertMercurePublishedOnce('/api/grocery_lists/'.$list->getId());
        $this->assertMercurePublishedOnce('/api/products/'.$product->getId());
        $this->assertMercurePublishedOnce('/api/stores/'.$store->getId());
        $this->assertElasticsearchIndexDispatchedFor(GroceryList::class, (string) $list->getId());
        $this->assertElasticsearchIndexDispatchedFor(Product::class, (string) $product->getId());
        $this->assertElasticsearchIndexDispatchedFor(Store::class, (string) $store->getId());
    }

    public function testDeletingAStorePublishesItsDeletionAndTheProductsItDetachedFrom(): void
    {
        $storeId = (string) $this->getFixture('supermarket')->getId();
        $tomatoId = (string) $this->getFixture('tomato')->getId();

        $this->dispatch(new DeleteStoreCommand(storeId: $storeId));

        $this->assertMercureDeletePublished('/api/stores/'.$storeId);
        $this->assertMercurePublishedOnce('/api/products/'.$tomatoId);
        $this->assertMercurePublishedOnce('/api/ingredients/'.$tomatoId);
        $this->assertElasticsearchDeleteDispatched('stores');
        $this->assertElasticsearchIndexDispatchedFor(Ingredient::class, $tomatoId);
    }

    public function testDeletingAProductPublishesItsDeletionAndTheLinesItLeavesOnTheList(): void
    {
        $tomatoId = (string) $this->getFixture('tomato')->getId();
        $listId = (string) $this->getFixture('grocery_list')->getId();

        $this->dispatch(new DeleteProductCommand(productId: $tomatoId));

        $this->assertMercureDeletePublished('/api/products/'.$tomatoId);
        $this->assertMercureDeletePublished('/api/ingredients/'.$tomatoId);
        $this->assertMercurePublishedOnce('/api/grocery_lists/'.$listId);
        $this->assertElasticsearchIndexDispatchedFor(GroceryList::class, $listId);
    }

    public function testEditingALineTellsTheListAndTheProductOnceEach(): void
    {
        $itemId = (string) $this->getFixture('item_tomato')->getId();
        $listId = (string) $this->getFixture('grocery_list')->getId();
        $tomatoId = (string) $this->getFixture('tomato')->getId();

        $this->dispatch(new EditGroceryItemCommand(
            groceryItemId: $itemId,
            userId: (string) $this->getFixture('test_user')->getId(),
            quantity: 5,
            unit: 'kg',
            category: 'other',
        ));

        $this->assertMercurePublishedOnce('/api/grocery_lists/'.$listId);
        $this->assertMercurePublishedOnce('/api/products/'.$tomatoId);
        self::assertSame([$tomatoId], $this->reindexedIdsOf(Ingredient::class), 'The product is reindexed once');
        self::assertSame([$listId], $this->reindexedIdsOf(GroceryList::class), 'The list is reindexed once');
    }
}
