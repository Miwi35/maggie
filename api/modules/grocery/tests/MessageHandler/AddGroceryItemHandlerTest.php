<?php

namespace Maggie\Grocery\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Message\AddGroceryItemCommand;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class AddGroceryItemHandlerTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/fixtures/add_item_matching.yaml');
        $this->resetAsyncTransport();
    }

    /** @return iterable<string, array{string, string}> */
    public static function labelsOfAnExistingProduct(): iterable
    {
        yield 'plural' => ['poireaux', 'Poireau'];
        yield 'capitalised plural' => ['Poireaux', 'Poireau'];
        yield 'upper case' => ['POIREAU', 'Poireau'];
        yield 'without accents' => ['creme fraiche', 'Crème fraîche'];
        yield 'plural of an accented name' => ['Crèmes fraîches', 'Crème fraîche'];
    }

    #[DataProvider('labelsOfAnExistingProduct')]
    public function testTheLineJoinsTheExistingProductInsteadOfCreatingAnother(string $label, string $productName): void
    {
        $user = $this->getFixture('test_user');
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new AddGroceryItemCommand((string) $user->getId(), $label));

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();
        self::assertCount(2, $em->getRepository(Product::class)->findAll());
        $lines = $em->getRepository(GroceryItem::class)->findAll();
        self::assertCount(1, $lines);
        self::assertSame($productName, $lines[0]->getProduct()?->getName());
    }
}
