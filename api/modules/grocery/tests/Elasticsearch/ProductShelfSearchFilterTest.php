<?php

declare(strict_types=1);

namespace Maggie\Grocery\Tests\Elasticsearch;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Elasticsearch\Query\ElasticsearchFilterTranslator;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Elasticsearch\ProductShelfSearchFilter;
use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Query\ProductShelfRequestReader;
use Maggie\Grocery\Repository\AisleRepository;
use PHPUnit\Framework\TestCase;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;

/**
 * The request `?store=&aisle=&order[aisle]=` as Elasticsearch gets it (MAG-604).
 * {@see \Maggie\Grocery\Tests\Api\ProductShelfApiTest} asks Doctrine the same.
 */
final class ProductShelfSearchFilterTest extends TestCase
{
    private User $user;
    private Store $carrefour;
    private Store $lidl;
    private Aisle $cremerie;
    private Aisle $frais;
    private ProductShelfSearchFilter $filter;
    private ElasticsearchFilterTranslator $translator;

    protected function setUp(): void
    {
        $this->user = (new User())->setEmail('shelf@example.com')->setGoogleId('google-shelf')->setName('Shelf');
        $this->carrefour = (new Store())->setName('Carrefour')->setUser($this->user);
        $this->lidl = (new Store())->setName('Lidl')->setUser($this->user);
        $this->cremerie = (new Aisle())->setStore($this->carrefour)->setName('Crèmerie')->setPosition(2)->setUser($this->user);
        $this->frais = (new Aisle())->setStore($this->lidl)->setName('Frais')->setPosition(1)->setUser($this->user);

        $aisles = $this->createStub(AisleRepository::class);
        $aisles->method('find')->willReturnCallback(fn (mixed $id): ?Aisle => match ((string) $id) {
            (string) $this->cremerie->getId() => $this->cremerie,
            (string) $this->frais->getId() => $this->frais,
            default => null,
        });
        $security = $this->createStub(Security::class);
        $security->method('getUser')->willReturn($this->user);

        $this->filter = new ProductShelfSearchFilter(new ProductShelfRequestReader($security, $aisles));
        $this->translator = new ElasticsearchFilterTranslator();
    }

    /**
     * @param array<string, mixed> $filters
     *
     * @return array{must: array<int, array<string, mixed>>, filter: array<int, array<string, mixed>>, sort: array<int, array<string, mixed>>}
     */
    private function translate(array $filters): array
    {
        $meta = (new IndexMetadataReader())->read(Product::class);
        self::assertNotNull($meta);

        return $this->translator->translate($filters, $meta['fields'], $meta['relations'], [], $this->filter);
    }

    private function store(Store $store): string
    {
        return '/api/stores/'.$store->getId();
    }

    public function testItAnswersProductsAndIngredients(): void
    {
        self::assertTrue($this->filter->supports(Product::class));
        self::assertTrue($this->filter->supports(Ingredient::class));
        self::assertFalse($this->filter->supports(Store::class));
    }

    public function testAStoreIsItsUsualStoreItsFallbackStoreOrAnAisleInIt(): void
    {
        $translated = $this->translate(['store' => $this->store($this->carrefour)]);

        $id = (string) $this->carrefour->getId();
        self::assertSame([['bool' => [
            'should' => [
                ['term' => ['preferredStoreId' => $id]],
                ['term' => ['fallbackStoreId' => $id]],
                ['nested' => ['path' => 'aisles', 'query' => ['term' => ['aisles.storeId' => $id]]]],
            ],
            'minimum_should_match' => 1,
        ]]], $translated['filter']);
        self::assertSame([], $translated['must']);
    }

    public function testTheStoreIsNotAlsoATermOnAFieldTheIndexDoesNotHave(): void
    {
        $translated = $this->translate(['store' => $this->store($this->carrefour), 'aisle' => (string) $this->cremerie->getId()]);

        self::assertStringNotContainsString('"store"', json_encode($translated, JSON_THROW_ON_ERROR));
        self::assertStringNotContainsString('"aisle"', json_encode($translated, JSON_THROW_ON_ERROR));
    }

    public function testAnAisleKeepsTheProductsInThatAisle(): void
    {
        $translated = $this->translate(['store' => (string) $this->carrefour->getId(), 'aisle' => '/api/aisles/'.$this->cremerie->getId()]);

        self::assertSame([['nested' => ['path' => 'aisles', 'query' => ['term' => ['aisles.aisleId' => (string) $this->cremerie->getId()]]]]], $translated['filter']);
    }

    public function testNoneKeepsTheProductsOfTheStoreWithoutAnAisleThere(): void
    {
        $translated = $this->translate(['store' => $this->store($this->carrefour), 'aisle' => 'none']);

        $id = (string) $this->carrefour->getId();
        self::assertCount(2, $translated['filter']);
        self::assertSame(
            ['bool' => ['must_not' => [['nested' => ['path' => 'aisles', 'query' => ['term' => ['aisles.storeId' => $id]]]]]]],
            $translated['filter'][1],
        );
    }

    public function testAnIdentifierThatIsNoneOrAnAisleThatIsNotTheUsersMatchesNothing(): void
    {
        $notAnId = $this->translate(['store' => 'nope']);
        self::assertSame([['ids' => ['values' => []]]], $notAnId['filter'], 'An empty answer, never the whole collection');

        $noSuchAisle = $this->translate(['store' => $this->store($this->carrefour), 'aisle' => '01ARZ3NDEKTSV4RRFFQ69G5FAV']);
        self::assertSame([['ids' => ['values' => []]]], $noSuchAisle['filter']);
    }

    public function testAStoreThatIsNotTheUsersMatchesNothingForWantOfDocuments(): void
    {
        // Every query carries the user's id: the documents of a stranger's store are never theirs.
        $translated = $this->translate(['store' => '01ARZ3NDEKTSV4RRFFQ69G5FAV']);

        self::assertCount(1, $translated['filter']);
        self::assertArrayHasKey('bool', $translated['filter'][0]);
    }

    public function testAnAisleOfAnotherStoreIsABadRequest(): void
    {
        $this->expectException(BadRequestHttpException::class);

        $this->translate(['store' => $this->store($this->carrefour), 'aisle' => (string) $this->frais->getId()]);
    }

    public function testAnAisleOrAnAisleSortWithoutAStoreIsABadRequest(): void
    {
        try {
            $this->translate(['aisle' => (string) $this->cremerie->getId()]);
            self::fail('aisle without store should be refused');
        } catch (BadRequestHttpException) {
        }

        $this->expectException(BadRequestHttpException::class);
        $this->translate(['order' => ['aisle' => 'asc']]);
    }

    public function testTheAisleSortReadsThePositionInThatStoreAndPutsNoAisleLast(): void
    {
        $id = (string) $this->carrefour->getId();

        foreach (['asc', 'desc'] as $direction) {
            $translated = $this->translate(['store' => $id, 'order' => ['aisle' => $direction]]);

            self::assertSame([[
                'aisles.aislePosition' => [
                    'order' => $direction,
                    'missing' => '_last',
                    'nested' => ['path' => 'aisles', 'filter' => ['term' => ['aisles.storeId' => $id]]],
                ],
            ]], $translated['sort']);
        }
    }

    public function testTheSortsKeepTheOrderAskedAndTheOthersStayGeneric(): void
    {
        $id = (string) $this->carrefour->getId();

        $translated = $this->translate(['store' => $id, 'order' => ['name' => 'asc', 'aisle' => 'asc', 'stockState' => 'desc']]);

        self::assertSame(['name.keyword', 'aisles.aislePosition', 'stockState'], array_map(static fn (array $sort): string => array_key_first($sort), $translated['sort']));
    }

    public function testTheProductIndexHoldsWhatTheFilterReads(): void
    {
        $meta = (new IndexMetadataReader())->read(Product::class);

        self::assertSame('nested', $meta['fields']['aisles']['type']);
        self::assertSame('keyword', $meta['fields']['aisles']['properties']['storeId']['type']);
        self::assertSame('keyword', $meta['fields']['aisles']['properties']['aisleId']['type']);
        self::assertSame('integer', $meta['fields']['aisles']['properties']['aislePosition']['type']);
        self::assertSame('preferredStoreId', $meta['relations']['preferredStore']['sourceField']);
        self::assertSame('fallbackStoreId', $meta['relations']['fallbackStore']['sourceField']);
    }
}
