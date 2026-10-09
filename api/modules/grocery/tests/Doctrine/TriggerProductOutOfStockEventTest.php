<?php

namespace Maggie\Grocery\Tests\Doctrine;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\DomainEvent\PendingDomainEvents;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\ProductStockState;
use Maggie\Grocery\Event\ProductOutOfStockEvent;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class TriggerProductOutOfStockEventTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/update_stock.yaml');
        $this->pending()->reset();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function pending(): PendingDomainEvents
    {
        return self::getContainer()->get(PendingDomainEvents::class);
    }

    private function rice(): Product
    {
        return $this->em()->find(Product::class, $this->getFixture('rice')->getId());
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

    public function testLeavingInStockTriggersTheEventOnceTheProductIsSaved(): void
    {
        $rice = $this->rice();
        $rice->setStockState(ProductStockState::Out);
        $this->em()->flush();

        $events = $this->pending()->release();
        self::assertCount(1, $events);
        self::assertInstanceOf(ProductOutOfStockEvent::class, $events[0]);
        self::assertSame($rice, $events[0]->product);
    }

    public function testSavingAgainWhileOutOrMovingFromLowToOutTriggersNothing(): void
    {
        $rice = $this->rice();
        $rice->setStockState(ProductStockState::Low);
        $this->em()->flush();
        $this->pending()->reset();

        $rice->setStockState(ProductStockState::Out);
        $this->em()->flush();
        $rice->setName('Riz basmati');
        $this->em()->flush();

        self::assertSame([], $this->pending()->release());
    }

    public function testTheEventIsOnlyDeferredNotHandledByTheListener(): void
    {
        $rice = $this->rice();
        $rice->setStockState(ProductStockState::Out);
        $this->em()->flush();

        $this->em()->clear();
        self::assertCount(0, $this->em()->getRepository(\Maggie\Grocery\Entity\GroceryItem::class)->findAll(), 'the listener has no business effect');
    }

    public function testAProductCreatedShortTriggersTheEventAndOneCreatedInStockDoesNot(): void
    {
        $this->newProduct(ProductStockState::InStock);
        $this->em()->flush();
        self::assertSame([], $this->pending()->release());

        $out = $this->newProduct(ProductStockState::Out);
        $this->em()->flush();

        $events = $this->pending()->release();
        self::assertCount(1, $events);
        self::assertSame($out, $events[0]->product);
    }

    public function testAnotherEntityChangeTriggersNothing(): void
    {
        $rice = $this->rice();
        $rice->setName('Riz long');
        $this->em()->flush();

        self::assertSame([], $this->pending()->release());
    }
}
