<?php

namespace Maggie\Grocery\Tests\Specification;

use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\ProductStockState;
use Maggie\Grocery\Specification\IsProductOutOfStock;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

class IsProductOutOfStockTest extends TestCase
{
    /** @return iterable<string, array{?ProductStockState, ProductStockState, bool}> */
    public static function transitions(): iterable
    {
        yield 'in stock to low' => [ProductStockState::InStock, ProductStockState::Low, true];
        yield 'in stock to out' => [ProductStockState::InStock, ProductStockState::Out, true];
        yield 'low to out' => [ProductStockState::Low, ProductStockState::Out, false];
        yield 'out to low' => [ProductStockState::Out, ProductStockState::Low, false];
        yield 'out to out' => [ProductStockState::Out, ProductStockState::Out, false];
        yield 'low to low' => [ProductStockState::Low, ProductStockState::Low, false];
        yield 'back in stock' => [ProductStockState::Out, ProductStockState::InStock, false];
        yield 'in stock to in stock' => [ProductStockState::InStock, ProductStockState::InStock, false];
        yield 'created out' => [null, ProductStockState::Out, true];
        yield 'created low' => [null, ProductStockState::Low, true];
        yield 'created in stock' => [null, ProductStockState::InStock, false];
    }

    #[DataProvider('transitions')]
    public function testOnlyLeavingInStockOrBeingCreatedShortSatisfiesIt(?ProductStockState $before, ProductStockState $after, bool $expected): void
    {
        $product = (new Product())->setStockState($after);

        $specification = new IsProductOutOfStock(['stockState' => [$before, $after]]);
        self::assertSame($expected, $specification->isSatisfiedBy($product));

        $asDoctrineReadsIt = new IsProductOutOfStock(['stockState' => [$before?->value, $after->value]]);
        self::assertSame($expected, $asDoctrineReadsIt->isSatisfiedBy($product));
    }

    public function testAStateThatDidNotChangeIsNotSatisfied(): void
    {
        $product = (new Product())->setStockState(ProductStockState::Out);

        self::assertFalse((new IsProductOutOfStock(['name' => ['Riz', 'Riz basmati']]))->isSatisfiedBy($product));
        self::assertFalse((new IsProductOutOfStock([]))->isSatisfiedBy($product));
    }
}
