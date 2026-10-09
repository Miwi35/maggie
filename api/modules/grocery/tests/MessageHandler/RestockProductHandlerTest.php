<?php

namespace Maggie\Grocery\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Message\RestockProductCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

class RestockProductHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/update_stock.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function dispatch(string $productId): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new RestockProductCommand($productId));
    }

    public function testTheListWithTheNewLineIsPublishedAndReindexed(): void
    {
        $this->dispatch((string) $this->getFixture('rice')->getId());

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();
        $lines = $em->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $lines);
        self::assertSame(GroceryItemSource::Restock, $lines[0]->getSource());
        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testNothingIsPublishedWhenTheProductDoesNotRestockByItself(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $this->getFixture('rice')->setAutoRestock(false);
        $em->flush();
        $this->resetMercure();

        $this->dispatch((string) $this->getFixture('rice')->getId());

        $this->assertMercureUpdateCount(0);
    }

    public function testAnUnknownProductIsRefused(): void
    {
        $this->expectException(HandlerFailedException::class);

        $this->dispatch('01JZZZZZZZZZZZZZZZZZZZZZZZ');
    }
}
