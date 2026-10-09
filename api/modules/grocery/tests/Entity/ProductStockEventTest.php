<?php

namespace Maggie\Grocery\Tests\Entity;

use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\ProductStockState;
use Maggie\Grocery\Event\ProductStockRanLow;
use PHPUnit\Framework\TestCase;

class ProductStockEventTest extends TestCase
{
    public function testLeavingInStockRecordsOneEventWithTheNewState(): void
    {
        $product = new Product();

        $product->setStockState(ProductStockState::Out);

        $events = $product->releaseDomainEvents();
        self::assertCount(1, $events);
        self::assertInstanceOf(ProductStockRanLow::class, $events[0]);
        self::assertSame((string) $product->getId(), $events[0]->productId);
        self::assertSame(ProductStockState::Out, $events[0]->state);
        self::assertSame([], $product->releaseDomainEvents(), 'released events are forgotten');
    }

    public function testStayingShortRecordsNothing(): void
    {
        $product = new Product();
        $product->setStockState(ProductStockState::Low);
        $product->releaseDomainEvents();

        $product->setStockState(ProductStockState::Out);
        $product->setStockState(ProductStockState::Out);
        $product->setStockState(ProductStockState::Low);

        self::assertSame([], $product->releaseDomainEvents());
    }

    public function testStayingInStockRecordsNothing(): void
    {
        $product = new Product();

        $product->setStockState(ProductStockState::InStock);

        self::assertSame([], $product->releaseDomainEvents());
    }

    public function testBeingBackInStockBeforeTheFlushForgetsTheEvent(): void
    {
        $product = new Product();

        $product->setStockState(ProductStockState::Out);
        $product->setStockState(ProductStockState::InStock);

        self::assertSame([], $product->releaseDomainEvents());
    }

    public function testLeavingInStockAgainRecordsANewEvent(): void
    {
        $product = new Product();
        $product->setStockState(ProductStockState::Out);
        $product->releaseDomainEvents();
        $product->setStockState(ProductStockState::InStock);

        $product->setStockState(ProductStockState::Low);

        self::assertCount(1, $product->releaseDomainEvents());
    }
}
