<?php

namespace Maggie\Grocery\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Enum\ProductStockState;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * `store`, `aisle` and `order[name|aisle|stockState]` on the products and the
 * ingredients (MAG-604), on the Doctrine side — the tests never reach
 * Elasticsearch. {@see \Maggie\Grocery\Tests\Elasticsearch\ProductShelfSearchFilterTest}
 * holds the same requests for Elasticsearch.
 *
 * Carrefour: Pommes in « Fruits et légumes », Lait in « Crèmerie », Pain in
 * « Boulangerie », Savon and Farine (an ingredient, so a product too) with
 * none. Lidl: Lait in « Frais ».
 */
class ProductShelfApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/aisles.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        // Postgres sorts « É » with the E's and Elasticsearch after the Z's: no accent, the order holds on both.
        $this->em()->getRepository(Product::class)->find($this->id('eponges'))->setName('Savon');
        $this->em()->flush();
        $this->em()->clear();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);

        return $em;
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** @return list<string> the names of the members, in the order served; null when the API refused */
    private function names(string $uri, int $status = 200, bool $authenticated = true): array
    {
        $this->client->request('GET', $uri, [], [], array_merge(
            ['HTTP_ACCEPT' => 'application/ld+json'],
            $authenticated ? $this->authHeaders() : [],
        ));
        self::assertResponseStatusCodeSame($status, $uri.' '.$this->client->getResponse()->getContent());

        if (200 !== $status) {
            return [];
        }

        $data = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);

        return array_column($data['member'], 'name');
    }

    private function store(string $ref): string
    {
        return 'store='.urlencode('/api/stores/'.$this->id($ref));
    }

    private function aisle(string $ref): string
    {
        return 'aisle='.urlencode('/api/aisles/'.$this->id($ref));
    }

    public function testTheCollectionRequiresAuthentication(): void
    {
        $this->names('/api/products?'.$this->store('carrefour'), 401, authenticated: false);
    }

    public function testAStoreKeepsItsProductsAndNoOthers(): void
    {
        $names = $this->names('/api/products?'.$this->store('carrefour').'&order[name]=asc');

        self::assertSame(['Farine', 'Lait', 'Pain', 'Pommes', 'Savon'], $names);
        self::assertNotContains("Lait de l'autre", $names);
    }

    public function testAStoreAcceptsABareIdentifierAsWellAsItsIri(): void
    {
        $names = $this->names('/api/products?store='.$this->id('lidl'));

        self::assertSame(['Lait'], $names, 'At Lidl, only the milk has an aisle: it is not its usual store.');
    }

    public function testTheFallbackStoreCountsAsWellAsTheUsualOne(): void
    {
        $eponges = $this->em()->getRepository(Product::class)->find($this->id('eponges'));
        $eponges->setFallbackStore($this->em()->getRepository(Store::class)->find($this->id('lidl')));
        $this->em()->flush();
        $this->em()->clear();

        $names = $this->names('/api/products?'.$this->store('lidl').'&order[name]=asc');

        self::assertSame(['Lait', 'Savon'], $names);
    }

    public function testAnAisleKeepsItsProductsOnly(): void
    {
        self::assertSame(['Lait'], $this->names('/api/products?'.$this->store('carrefour').'&'.$this->aisle('cremerie')));
        self::assertSame(['Pommes'], $this->names('/api/products?'.$this->store('carrefour').'&'.$this->aisle('fruits')));
        self::assertSame(['Lait'], $this->names('/api/products?'.$this->store('lidl').'&'.$this->aisle('frais')));
    }

    public function testNoneKeepsTheProductsOfTheStoreWithoutAnAisleThere(): void
    {
        $names = $this->names('/api/products?'.$this->store('carrefour').'&aisle=none');

        self::assertEqualsCanonicalizing(['Savon', 'Farine'], $names, 'Lait has an aisle at Lidl, which does not matter at Carrefour.');
    }

    public function testNoneAtAStoreKeepsAProductWhoseAisleIsInAnotherStoreOnly(): void
    {
        $names = $this->names('/api/products?'.$this->store('lidl').'&aisle=none');

        self::assertSame([], $names, 'The only product of Lidl has its aisle there.');
    }

    public function testAnAisleWithoutAStoreIsRefused(): void
    {
        $this->names('/api/products?'.$this->aisle('cremerie'), 400);
        $this->names('/api/products?aisle=none', 400);
    }

    public function testAnAisleOfAnotherStoreIsRefused(): void
    {
        $this->names('/api/products?'.$this->store('carrefour').'&'.$this->aisle('frais'), 400);
    }

    public function testSortingByAisleWithoutAStoreIsRefused(): void
    {
        $this->names('/api/products?order[aisle]=asc', 400);
    }

    public function testAnotherUsersStoreOrAisleGivesNothing(): void
    {
        self::assertSame([], $this->names('/api/products?'.$this->store('other_store')));
        self::assertSame([], $this->names('/api/products?'.$this->store('carrefour').'&'.$this->aisle('other_aisle')));
        self::assertSame([], $this->names('/api/products?store=01ARZ3NDEKTSV4RRFFQ69G5FAV'));
        self::assertSame([], $this->names('/api/products?store=not-an-id'));
    }

    public function testTheAisleSortFollowsTheWayThroughTheStoreWithNoAisleLast(): void
    {
        $ascending = $this->names('/api/products?'.$this->store('carrefour').'&order[aisle]=asc');
        self::assertSame(['Pommes', 'Lait', 'Pain'], \array_slice($ascending, 0, 3));
        self::assertEqualsCanonicalizing(['Savon', 'Farine'], \array_slice($ascending, 3));

        $descending = $this->names('/api/products?'.$this->store('carrefour').'&order[aisle]=desc');
        self::assertSame(['Pain', 'Lait', 'Pommes'], \array_slice($descending, 0, 3), 'Lait is in « Crèmerie » at Carrefour, whatever its aisle at Lidl');
        self::assertEqualsCanonicalizing(['Savon', 'Farine'], \array_slice($descending, 3), '« Sans rayon » closes the store whichever way the aisles run');
    }

    public function testTheSortsApplyInTheOrderAsked(): void
    {
        self::assertSame(
            ['Pommes', 'Lait', 'Pain', 'Farine', 'Savon'],
            $this->names('/api/products?'.$this->store('carrefour').'&order[aisle]=asc&order[name]=asc'),
        );
        self::assertSame(
            ['Farine', 'Lait', 'Pain', 'Pommes', 'Savon'],
            $this->names('/api/products?'.$this->store('carrefour').'&order[name]=asc&order[aisle]=asc'),
        );
    }

    public function testTheStockSortPutsTheStateInItsOrder(): void
    {
        $em = $this->em();
        $em->getRepository(Product::class)->find($this->id('pain'))->setStockState(ProductStockState::Out);
        $em->getRepository(Product::class)->find($this->id('lait'))->setStockState(ProductStockState::Low);
        $em->flush();
        $em->clear();

        $names = $this->names('/api/products?'.$this->store('carrefour').'&order[stockState]=asc&order[name]=asc');

        // in_stock < low < out, as the strings sort.
        self::assertSame(['Farine', 'Pommes', 'Savon', 'Lait', 'Pain'], $names);
    }

    public function testTheNameSortStillWorksWithoutAStore(): void
    {
        $names = $this->names('/api/products?order[name]=desc');

        self::assertSame(['Pommes', 'Pain', 'Lait'], array_values(array_intersect($names, ['Pommes', 'Pain', 'Lait'])));
    }

    public function testIngredientsAnswerTheSameFilters(): void
    {
        self::assertSame(['Farine'], $this->names('/api/ingredients?'.$this->store('carrefour').'&aisle=none'));
        self::assertSame([], $this->names('/api/ingredients?'.$this->store('lidl')));
        $this->names('/api/ingredients?'.$this->aisle('cremerie'), 400);
    }

    public function testMovingAnAisleReindexesTheProductsOfItsStoreOnly(): void
    {
        $this->client->request('PATCH', '/api/aisles/'.$this->id('fruits'), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['position' => 3], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        foreach (['pommes', 'lait', 'pain'] as $ref) {
            $this->assertElasticsearchIndexDispatchedFor(Product::class, $this->id($ref));
        }
        // Lait is in Lidl's « Frais » too, but it is reindexed once, for Carrefour's sake: nothing to say about Savon.
        self::assertNotContains($this->id('eponges'), $this->reindexedIdsOf(Product::class));
        self::assertSame(
            ['Lait', 'Pain', 'Pommes'],
            array_values(array_intersect($this->names('/api/products?'.$this->store('carrefour').'&order[aisle]=asc'), ['Lait', 'Pain', 'Pommes'])),
        );
    }
}
