<?php

namespace Maggie\Grocery\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Message\AddRecurringGroceryItemCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

class AddRecurringGroceryItemHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Command/fixtures/AddDueRecurringGroceryItemsCommandTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function dispatch(string $itemId): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new AddRecurringGroceryItemCommand($itemId));
    }

    private function idOf(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    public function testTheListWithTheNewLineIsPublishedAndReindexed(): void
    {
        $this->dispatch($this->idOf('recurring_rice'));

        $this->em()->clear();
        $lines = $this->em()->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $lines);
        self::assertSame(GroceryItemSource::Recurring, $lines[0]->getSource());
        self::assertSame('Riz', $lines[0]->getLabel());
        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
        $this->assertElasticsearchIndexDispatched(RecurringGroceryItem::class);
    }

    public function testNothingIsPublishedWhenTheItemIsNotDue(): void
    {
        $this->dispatch($this->idOf('recurring_coffee'));

        $this->assertMercureUpdateCount(0);
        $this->em()->clear();
        self::assertSame([], $this->em()->getRepository(GroceryItem::class)->findAll());
    }

    public function testAnUnknownItemIsRefused(): void
    {
        $this->expectException(HandlerFailedException::class);

        $this->dispatch('01JZZZZZZZZZZZZZZZZZZZZZZZ');
    }
}
