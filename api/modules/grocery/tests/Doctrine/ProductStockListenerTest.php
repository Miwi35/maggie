<?php

namespace Maggie\Grocery\Tests\Doctrine;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Events;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Doctrine\ProductStockListener;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\ProductStockState;
use Maggie\Grocery\Event\ProductOutOfStockEvent;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

class ProductStockListenerTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    /** @var list<array{object, mixed}> the events the listener sent, with the stock state stored when it sent them */
    private array $dispatched = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/update_stock.yaml');
        $this->dispatched = [];

        // The real listener keeps running; this one only records what it would send.
        $this->em()->getEventManager()->addEventListener(
            [Events::onFlush, Events::postFlush],
            new ProductStockListener($this->spyBus()),
        );
        // Nothing restocks here: the chain behind the event has its own tests.
        $this->rice()->setAutoRestock(false);
        $this->em()->flush();
        $this->dispatched = [];
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function rice(): Product
    {
        return $this->em()->find(Product::class, $this->getFixture('rice')->getId());
    }

    private function storedStockStateOf(Product $product): mixed
    {
        return $this->em()->createQuery('SELECT p.stockState FROM '.Product::class.' p WHERE p.id = :id')
            ->setParameter('id', $product->getId(), 'ulid')
            ->getSingleScalarResult();
    }

    private function spyBus(): MessageBusInterface
    {
        return new class($this) implements MessageBusInterface {
            public function __construct(private readonly ProductStockListenerTest $test)
            {
            }

            public function dispatch(object $message, array $stamps = []): Envelope
            {
                $this->test->record($message);

                return new Envelope($message);
            }
        };
    }

    /** @internal */
    public function record(object $event): void
    {
        $product = $this->em()->find(Product::class, $event->productId);
        $this->dispatched[] = [$event, $this->storedStockStateOf($product)];
    }

    private function newProduct(ProductStockState $state): Product
    {
        $product = (new Product())
            ->setUser($this->em()->find(User::class, $this->getFixture('test_user')->getId()))
            ->setName('Sel')
            ->setCategory(ProductCategory::Other)
            ->setStockState($state);
        $this->em()->persist($product);

        return $product;
    }

    public function testLeavingInStockSendsTheEventWithTheIdOnlyOnceTheProductIsStored(): void
    {
        $rice = $this->rice();
        $rice->setStockState(ProductStockState::Out);
        $this->em()->flush();

        self::assertCount(1, $this->dispatched);
        [$event, $storedState] = $this->dispatched[0];
        self::assertEquals(new ProductOutOfStockEvent((string) $rice->getId()), $event);
        self::assertSame('out', $storedState, 'the event goes out after the flush, not during it');
    }

    public function testSavingAgainWhileOutOrMovingFromLowToOutSendsNothing(): void
    {
        $rice = $this->rice();
        $rice->setStockState(ProductStockState::Low);
        $this->em()->flush();
        $this->dispatched = [];

        $rice->setStockState(ProductStockState::Out);
        $this->em()->flush();
        $rice->setName('Riz basmati');
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }

    public function testTheListenerHasNoBusinessEffectOfItsOwn(): void
    {
        $rice = $this->rice();
        $rice->setStockState(ProductStockState::Out);
        $this->em()->flush();

        $this->em()->clear();
        self::assertCount(0, $this->em()->getRepository(GroceryItem::class)->findAll());
    }

    public function testAProductCreatedShortSendsTheEventAndOneCreatedInStockDoesNot(): void
    {
        $this->newProduct(ProductStockState::InStock);
        $this->em()->flush();
        self::assertSame([], $this->dispatched);

        $out = $this->newProduct(ProductStockState::Out);
        $this->em()->flush();

        self::assertCount(1, $this->dispatched);
        self::assertEquals(new ProductOutOfStockEvent((string) $out->getId()), $this->dispatched[0][0]);
    }

    public function testAnotherChangeSendsNothing(): void
    {
        $rice = $this->rice();
        $rice->setName('Riz long');
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }
}
