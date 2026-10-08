<?php

namespace Maggie\Grocery\Tests\Entity;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\Hydrator\ElasticsearchEntityHydrator;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\ProductStockState;
use Maggie\Grocery\Enum\Unit;
use PHPUnit\Framework\TestCase;

/**
 * Lists and items of indexed entities are rebuilt from the Elasticsearch document
 * alone: a store the hydrator does not know how to rebuild comes back empty to the
 * client, so the preferred store looks saved, then disappears (MAG-190).
 */
class ProductSearchDocumentTest extends TestCase
{
    private User $user;
    private Store $preferred;
    private Store $fallback;
    private Product $product;

    protected function setUp(): void
    {
        $user = $this->user = new User();
        $user->setEmail('courses@example.com');
        $user->setGoogleId('google-courses');
        $user->setName('Courses');

        $this->preferred = (new Store())->setName('Halles du voisin')->setUser($user);
        $this->fallback = (new Store())->setName('Biocoop')->setUser($user);

        $this->product = (new Product())
            ->setName('Câpres')
            ->setCategory(ProductCategory::Other)
            ->setUser($user)
            ->setPreferredStore($this->preferred)
            ->setFallbackStore($this->fallback)
            ->setShelfLifeDays(30);
    }

    private function hydrate(Product $product): Product
    {
        $em = $this->createStub(EntityManagerInterface::class);
        $em->method('getReference')->willReturnCallback(
            fn (string $class, mixed $id): ?object => match (true) {
                User::class === $class => $this->user,
                (string) $this->preferred->getId() === (string) $id => $this->preferred,
                (string) $this->fallback->getId() === (string) $id => $this->fallback,
                default => null,
            },
        );

        // Through JSON, as Elasticsearch hands it back: 500.0 comes back as 500.
        $source = json_decode(json_encode($product->toSearchDocument(), JSON_THROW_ON_ERROR), true, 512, JSON_THROW_ON_ERROR);
        $source['id'] = (string) $product->getId();

        $hydrated = (new ElasticsearchEntityHydrator($em))->hydrate($source, Product::class);
        self::assertInstanceOf(Product::class, $hydrated);

        return $hydrated;
    }

    public function testStoresAndShelfLifeSurviveTheRoundTripThroughElasticsearch(): void
    {
        $hydrated = $this->hydrate($this->product);

        self::assertSame((string) $this->preferred->getId(), (string) $hydrated->getPreferredStore()?->getId());
        self::assertSame((string) $this->fallback->getId(), (string) $hydrated->getFallbackStore()?->getId());
        self::assertSame(30, $hydrated->getShelfLifeDays());
    }

    public function testAProductWithoutStoresStaysWithoutStores(): void
    {
        $this->product->setPreferredStore(null)->setFallbackStore(null);

        $hydrated = $this->hydrate($this->product);

        self::assertNull($hydrated->getPreferredStore());
        self::assertNull($hydrated->getFallbackStore());
    }

    public function testThePackagingSurvivesTheRoundTripThroughElasticsearch(): void
    {
        $this->product->setPackagingUnit(Unit::Pack)->setPackagingSize(500)->setPackagingSizeUnit(Unit::Gram);

        $hydrated = $this->hydrate($this->product);

        self::assertSame(Unit::Pack, $hydrated->getPackagingUnit());
        self::assertSame(500.0, $hydrated->getPackagingSize());
        self::assertSame(Unit::Gram, $hydrated->getPackagingSizeUnit());
    }

    public function testTheMercurePayloadCarriesThePackaging(): void
    {
        $this->product->setPackagingUnit(Unit::Jar);

        $payload = $this->product->toMercurePayload();

        self::assertSame('jar', $payload['packagingUnit']);
        self::assertNull($payload['packagingSize']);
        self::assertNull($payload['packagingSizeUnit']);
    }

    public function testTheStockSurvivesTheRoundTripThroughElasticsearch(): void
    {
        $this->product->setStockState(ProductStockState::Low)->setRestockQuantity(2)->setAutoRestock(true);

        $hydrated = $this->hydrate($this->product);

        self::assertSame(ProductStockState::Low, $hydrated->getStockState());
        self::assertSame(2, $hydrated->getRestockQuantity());
        self::assertTrue($hydrated->isAutoRestock());
    }

    public function testAProductIsInStockUntilToldOtherwise(): void
    {
        $hydrated = $this->hydrate($this->product);

        self::assertSame(ProductStockState::InStock, $hydrated->getStockState());
        self::assertNull($hydrated->getRestockQuantity());
        self::assertFalse($hydrated->isAutoRestock());
    }

    public function testTheMercurePayloadSpellsTheStockLikeRest(): void
    {
        $this->product->setStockState(ProductStockState::Out)->setRestockQuantity(3)->setAutoRestock(true);

        $payload = $this->product->toMercurePayload();

        self::assertSame('out', $payload['stockState']);
        self::assertSame(3, $payload['restockQuantity']);
        self::assertTrue($payload['autoRestock']);
        self::assertArrayNotHasKey('isAutoRestock', $payload);
    }
}
