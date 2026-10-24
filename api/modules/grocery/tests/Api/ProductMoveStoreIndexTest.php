<?php

namespace Maggie\Grocery\Tests\Api;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Message\UpdateProductCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * The search document is built from the entity the write has just changed, not
 * from a fresh read: moving a product to another store and another aisle in one
 * save removes a row and adds one in the same collection (MAG-604, recette).
 */
class ProductMoveStoreIndexTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    public function testMovingAProductToAnotherStoreAndAisleIndexesAListOfTheNewAisleOnly(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/aisles.yaml');
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $bus = self::getContainer()->get('messenger.bus.default');
        \assert($bus instanceof MessageBusInterface);

        $id = (string) $this->getFixture('pommes')->getId();
        $lidl = (string) $this->getFixture('lidl')->getId();
        $frais = (string) $this->getFixture('frais')->getId();
        $em->clear();

        $bus->dispatch(new UpdateProductCommand(
            productId: $id,
            preferredStoreId: $lidl,
            aisles: [['store' => $lidl, 'aisle' => $frais]],
        ));

        $product = $em->find(Product::class, $id);
        \assert($product instanceof Product);
        $aisles = $product->toSearchDocument()['aisles'];

        // A JSON object (`{"1": …}`) is not the nested list the index maps.
        self::assertTrue(array_is_list($aisles), 'The aisles are indexed as a JSON list.');
        self::assertSame([['storeId' => $lidl, 'aisleId' => $frais, 'aislePosition' => 1]], $aisles);
    }
}
