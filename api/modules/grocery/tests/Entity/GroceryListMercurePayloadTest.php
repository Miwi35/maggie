<?php

namespace Maggie\Grocery\Tests\Entity;

use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\ProductStockState;
use Maggie\Grocery\Enum\Unit;
use PHPUnit\Framework\TestCase;

/**
 * A second screen replaces its lines with the Mercure payload: a product that
 * lost its packaging on the way would turn « 2 paquets » back into a free
 * quantity until the next reload (MAG-299).
 */
class GroceryListMercurePayloadTest extends TestCase
{
    public function testTheProductOfALineCarriesItsPackagingAndItsStockState(): void
    {
        $user = (new User())->setEmail('courses@example.com')->setGoogleId('google-courses')->setName('Courses');
        $rice = (new Product())
            ->setName('Riz')
            ->setCategory(ProductCategory::Grain)
            ->setUser($user)
            ->setPackagingUnit(Unit::Pack)
            ->setPackagingSize(500)
            ->setPackagingSizeUnit(Unit::Gram)
            ->setStockState(ProductStockState::Low);
        $list = (new GroceryList())->setUser($user);
        $list->addItem(
            (new GroceryItem())
                ->setProduct($rice)
                ->setQuantity(2)
                ->setUnit(Unit::Pack)
                ->setSource(GroceryItemSource::Manual),
        );

        $payload = $list->toMercurePayload();

        self::assertSame('pack', $payload['items'][0]['unit']);
        self::assertSame('pack', $payload['items'][0]['product']['packagingUnit']);
        self::assertSame(500.0, $payload['items'][0]['product']['packagingSize']);
        self::assertSame('g', $payload['items'][0]['product']['packagingSizeUnit']);
        self::assertSame('low', $payload['items'][0]['product']['stockState']);
    }
}
