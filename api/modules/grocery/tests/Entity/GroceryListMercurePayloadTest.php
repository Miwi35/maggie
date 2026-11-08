<?php

namespace Maggie\Grocery\Tests\Entity;

use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
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

    public function testALineMissingInAStoreCarriesWhenAndTheStoreItWasMissingFrom(): void
    {
        $user = (new User())->setEmail('courses@example.com')->setGoogleId('google-courses')->setName('Courses');
        $supermarket = (new Store())->setName('Supermarché')->setVisitOrder(1)->setUser($user);
        $greengrocer = (new Store())->setName('Primeur')->setVisitOrder(2)->setUser($user);
        $tomato = (new Product())
            ->setName('Tomates')
            ->setCategory(ProductCategory::Produce)
            ->setUser($user)
            ->setFallbackStore($greengrocer);
        $list = (new GroceryList())->setUser($user);
        $missing = (new GroceryItem())->setProduct($tomato)->setStore($supermarket)->setSource(GroceryItemSource::Manual);
        $list->addItem($missing);
        $list->addItem((new GroceryItem())->setCustomLabel('Savon')->setSource(GroceryItemSource::Manual));
        $missing->markMissing(new \DateTimeImmutable('2026-11-08T10:00:00+00:00'));

        $payload = $list->toMercurePayload();
        $document = $list->toSearchDocument();

        self::assertSame('2026-11-08T10:00:00+00:00', $payload['items'][0]['missingAt']);
        self::assertSame(['id' => (string) $supermarket->getId(), 'name' => 'Supermarché', 'visitOrder' => 1], $payload['items'][0]['missingFromStore']);
        self::assertSame('Primeur', $payload['items'][0]['store']['name']);
        self::assertNull($payload['items'][1]['missingAt']);
        self::assertNull($payload['items'][1]['missingFromStore']);
        self::assertSame('2026-11-08T10:00:00+00:00', $document['items'][0]['missingAt']);
        self::assertSame((string) $supermarket->getId(), $document['items'][0]['missingFromStoreId']);
        self::assertNull($document['items'][1]['missingFromStoreId']);
    }
}
