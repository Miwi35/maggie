<?php

namespace Maggie\Grocery\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\ProductAisle;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Enum\GroceryItemSource;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * `/api/aisles`, the product's `aisles` and the grocery list's `aisle` per
 * line, as the admin and the app use them (MAG-493).
 */
class AisleApiTest extends WebTestCase
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
    }

    private function load(): void
    {
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/aisles.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->em()->clear();
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

    /**
     * @param array<string, mixed>|null $payload
     *
     * @return array<string, mixed>
     */
    private function call(string $method, string $uri, ?array $payload = null, bool $authenticated = true): array
    {
        $headers = [
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];
        $this->client->request($method, $uri, [], [], $authenticated ? array_merge($headers, $this->authHeaders()) : $headers, null !== $payload ? json_encode($payload, JSON_THROW_ON_ERROR) : null);

        $content = (string) $this->client->getResponse()->getContent();

        return '' !== $content ? json_decode($content, true, 512, JSON_THROW_ON_ERROR) : [];
    }

    /** @return array<string, int> name => position, of the store's aisles in the database */
    private function positionsIn(string $storeRef): array
    {
        $this->em()->clear();
        $positions = [];
        foreach ($this->em()->getRepository(Aisle::class)->findBy(['store' => $this->id($storeRef)], ['position' => 'ASC']) as $aisle) {
            $positions[$aisle->getName()] = $aisle->getPosition();
        }

        return $positions;
    }

    public function testEveryOperationRequiresAuthentication(): void
    {
        $this->load();

        $this->call('GET', '/api/aisles', authenticated: false);
        self::assertResponseStatusCodeSame(401);
        $this->call('POST', '/api/aisles', ['store' => '/api/stores/'.$this->id('carrefour'), 'name' => 'Surgelés'], authenticated: false);
        self::assertResponseStatusCodeSame(401);
        $this->call('PATCH', '/api/aisles/'.$this->id('cremerie'), ['name' => 'Frais'], authenticated: false);
        self::assertResponseStatusCodeSame(401);
        $this->call('DELETE', '/api/aisles/'.$this->id('cremerie'), authenticated: false);
        self::assertResponseStatusCodeSame(401);
    }

    public function testTheCollectionFilteredByStoreIsInTheOrderOfTheWay(): void
    {
        $this->load();

        $data = $this->call('GET', '/api/aisles?store='.urlencode('/api/stores/'.$this->id('carrefour')));

        self::assertResponseIsSuccessful();
        self::assertSame(['Fruits et légumes', 'Crèmerie', 'Boulangerie'], array_column($data['member'], 'name'));
        self::assertSame([1, 2, 3], array_column($data['member'], 'position'));
        self::assertSame('/api/stores/'.$this->id('carrefour'), $data['member'][0]['store']);
    }

    public function testAnotherUsersAislesAreNeitherListedNorReadable(): void
    {
        $this->load();

        $all = $this->call('GET', '/api/aisles');
        self::assertNotContains("Rayon de l'autre", array_column($all['member'], 'name'));
        self::assertCount(4, $all['member']);

        $this->call('GET', '/api/aisles/'.$this->id('other_aisle'));
        self::assertResponseStatusCodeSame(404);
        $this->call('PATCH', '/api/aisles/'.$this->id('other_aisle'), ['name' => 'Intrus']);
        self::assertResponseStatusCodeSame(404);
        $this->call('DELETE', '/api/aisles/'.$this->id('other_aisle'));
        self::assertResponseStatusCodeSame(404);
        self::assertCount(1, $this->positionsIn('other_store'));
    }

    public function testPostCreatesTheAisleLastAndPublishesIt(): void
    {
        $this->load();

        $data = $this->call('POST', '/api/aisles', ['store' => '/api/stores/'.$this->id('carrefour'), 'name' => 'Surgelés']);

        self::assertResponseStatusCodeSame(201);
        self::assertSame(4, $data['position']);
        self::assertSame(['Fruits et légumes' => 1, 'Crèmerie' => 2, 'Boulangerie' => 3, 'Surgelés' => 4], $this->positionsIn('carrefour'));
        $aisle = $this->em()->find(Aisle::class, $data['id']);
        self::assertSame($this->id('test_user'), (string) $aisle?->getUser()->getId());
        $this->assertMercureUpdatePublished('/api/aisles/'.$data['id']);
    }

    public function testPostRefusesABlankName(): void
    {
        $this->load();

        $this->call('POST', '/api/aisles', ['store' => '/api/stores/'.$this->id('carrefour'), 'name' => ' ']);

        self::assertResponseStatusCodeSame(400);
        self::assertCount(3, $this->positionsIn('carrefour'));
    }

    public function testPostRefusesANameTheStoreHasInAnotherCase(): void
    {
        $this->load();

        $this->call('POST', '/api/aisles', ['store' => '/api/stores/'.$this->id('carrefour'), 'name' => 'boulangerie']);

        self::assertResponseStatusCodeSame(400);
        self::assertCount(3, $this->positionsIn('carrefour'));
    }

    public function testPostRefusesAnotherUsersStoreAndAMissingStore(): void
    {
        $this->load();

        $this->call('POST', '/api/aisles', ['store' => '/api/stores/'.$this->id('other_store'), 'name' => 'Intrus']);
        self::assertResponseStatusCodeSame(400);
        $this->call('POST', '/api/aisles', ['name' => 'Sans magasin']);
        self::assertResponseStatusCodeSame(400);

        self::assertCount(1, $this->positionsIn('other_store'));
    }

    public function testPatchingThePositionMovesTheAisleAndTheListFollows(): void
    {
        $this->load();

        // The admin drops Boulangerie before Crèmerie.
        $this->call('PATCH', '/api/aisles/'.$this->id('boulangerie'), ['position' => 2]);

        self::assertResponseIsSuccessful();
        self::assertSame(['Fruits et légumes' => 1, 'Boulangerie' => 2, 'Crèmerie' => 3], $this->positionsIn('carrefour'));
        $this->assertMercureUpdatePublished('/api/aisles/'.$this->id('cremerie'));
        $payload = $this->mercurePayloadsOn('/api/grocery_lists/'.$this->id('grocery_list'));
        self::assertCount(1, $payload);
        $aisleOfMilk = array_values(array_filter($payload[0]['items'], fn (array $item) => 'Lait' === $item['label'] && 'Carrefour' === $item['store']['name']))[0]['aisle'];
        self::assertSame(['id' => $this->id('cremerie'), 'name' => 'Crèmerie', 'position' => 3], $aisleOfMilk);
        $this->assertElasticsearchIndexDispatched();
    }

    public function testPatchRenames(): void
    {
        $this->load();

        $this->call('PATCH', '/api/aisles/'.$this->id('cremerie'), ['name' => 'Produits laitiers']);

        self::assertResponseIsSuccessful();
        self::assertSame(['Fruits et légumes' => 1, 'Produits laitiers' => 2, 'Boulangerie' => 3], $this->positionsIn('carrefour'));
        $this->assertMercureUpdatePublished('/api/aisles/'.$this->id('cremerie'));
        $this->assertMercureUpdatePublished('/api/grocery_lists/'.$this->id('grocery_list'));
    }

    public function testPatchRefusesADuplicateNameAndAnotherStore(): void
    {
        $this->load();

        $this->call('PATCH', '/api/aisles/'.$this->id('cremerie'), ['name' => 'BOULANGERIE']);
        self::assertResponseStatusCodeSame(400);
        $this->call('PATCH', '/api/aisles/'.$this->id('cremerie'), ['store' => '/api/stores/'.$this->id('lidl')]);
        self::assertResponseStatusCodeSame(400);

        $this->em()->clear();
        $cremerie = $this->em()->find(Aisle::class, $this->id('cremerie'));
        self::assertSame('Crèmerie', $cremerie?->getName());
        self::assertSame($this->id('carrefour'), (string) $cremerie->getStore()->getId());
    }

    public function testDeleteDetachesItsProductsOnly(): void
    {
        $this->load();

        $this->call('DELETE', '/api/aisles/'.$this->id('cremerie'));

        self::assertResponseStatusCodeSame(204);
        self::assertSame(['Fruits et légumes' => 1, 'Boulangerie' => 2], $this->positionsIn('carrefour'));
        self::assertNotNull($this->em()->find(Product::class, $this->id('lait')));
        self::assertCount(1, $this->em()->getRepository(ProductAisle::class)->findBy(['product' => $this->id('lait')]), 'Lidl\'s « Frais » stays');
        $this->assertMercureDeletePublished('/api/aisles/'.$this->id('cremerie'));
        $this->assertMercureUpdatePublished('/api/grocery_lists/'.$this->id('grocery_list'));
    }

    public function testTheProductTellsItsAisleInEachStore(): void
    {
        $this->load();

        $data = $this->call('GET', '/api/products/'.$this->id('lait'));

        self::assertResponseIsSuccessful();
        self::assertEqualsCanonicalizing([
            ['store' => $this->id('carrefour'), 'aisle' => $this->id('cremerie')],
            ['store' => $this->id('lidl'), 'aisle' => $this->id('frais')],
        ], $data['aisles']);
        self::assertArrayNotHasKey('aisleAssignments', $data);
        self::assertArrayNotHasKey('aislesInput', $data);
    }

    public function testPatchingTheProductAislesReplacesThem(): void
    {
        $this->load();

        $data = $this->call('PATCH', '/api/products/'.$this->id('lait'), ['aisles' => [
            ['store' => '/api/stores/'.$this->id('carrefour'), 'aisle' => '/api/aisles/'.$this->id('fruits')],
        ]]);

        self::assertResponseIsSuccessful();
        self::assertSame([['store' => $this->id('carrefour'), 'aisle' => $this->id('fruits')]], $data['aisles']);
        $this->em()->clear();
        $assignments = $this->em()->getRepository(ProductAisle::class)->findBy(['product' => $this->id('lait')]);
        self::assertCount(1, $assignments, 'Lidl left out: « Sans rayon » there');
        self::assertSame($this->id('fruits'), (string) $assignments[0]->getAisle()->getId());
        $this->assertMercureUpdatePublished('/api/products/'.$this->id('lait'));
        $this->assertMercurePublishedOnce('/api/grocery_lists/'.$this->id('grocery_list'));
        $this->assertElasticsearchIndexDispatchedFor(Product::class, $this->id('lait'));
    }

    public function testAnIngredientIsPutInAnAisleThroughTheProductsEndpoint(): void
    {
        $this->load();

        $data = $this->call('PATCH', '/api/products/'.$this->id('farine'), ['aisles' => [
            ['store' => $this->id('carrefour'), 'aisle' => $this->id('boulangerie')],
        ]]);

        self::assertResponseIsSuccessful();
        self::assertSame([['store' => $this->id('carrefour'), 'aisle' => $this->id('boulangerie')]], $data['aisles']);
        $this->em()->clear();
        self::assertCount(1, $this->em()->getRepository(ProductAisle::class)->findBy(['product' => $this->id('farine')]));
    }

    public function testPatchingTheProductWithoutAislesKeepsThem(): void
    {
        $this->load();

        $this->call('PATCH', '/api/products/'.$this->id('lait'), ['name' => 'Lait demi-écrémé']);

        self::assertResponseIsSuccessful();
        self::assertCount(2, $this->em()->getRepository(ProductAisle::class)->findBy(['product' => $this->id('lait')]));
    }

    public function testPatchingTheProductWithAnAisleOfAnotherStoreIsRefused(): void
    {
        $this->load();

        $this->call('PATCH', '/api/products/'.$this->id('lait'), ['aisles' => [
            ['store' => $this->id('carrefour'), 'aisle' => $this->id('frais')],
        ]]);
        self::assertResponseStatusCodeSame(400);

        $this->call('PATCH', '/api/products/'.$this->id('lait'), ['aisles' => [
            ['store' => $this->id('carrefour'), 'aisle' => $this->id('other_aisle')],
        ]]);
        self::assertResponseStatusCodeSame(400);

        $this->em()->clear();
        self::assertCount(2, $this->em()->getRepository(ProductAisle::class)->findBy(['product' => $this->id('lait')]));
    }

    public function testTheGroceryListGivesEachLineItsAisleInItsStore(): void
    {
        $this->load();

        $data = $this->call('GET', '/api/grocery_lists/'.$this->id('grocery_list'));

        self::assertResponseIsSuccessful();
        $aisles = [];
        foreach ($data['items'] as $item) {
            $aisles[$item['label'].' @ '.$item['store']['name']] = $item['aisle']['name'] ?? null;
        }
        self::assertSame([
            'Éponges @ Carrefour' => null,
            'Lait @ Carrefour' => 'Crèmerie',
            'Pommes @ Carrefour' => 'Fruits et légumes',
            'Pain @ Carrefour' => 'Boulangerie',
            'Lait @ Lidl' => 'Frais',
        ], $aisles);
    }

    /** MAG-604: a line moved to the product's fallback store takes the aisle the product has there. */
    public function testALineInTheFallbackStoreTakesTheAisleOfThatStoreOrNone(): void
    {
        $this->load();
        $em = $this->em();
        $lidl = $em->find(Store::class, $this->id('lidl'));
        $list = $em->find(GroceryList::class, $this->id('grocery_list'));
        $em->find(Product::class, $this->id('lait'))->setFallbackStore($lidl);
        $pain = $em->find(Product::class, $this->id('pain'))->setFallbackStore($lidl);
        $em->persist((new GroceryItem())->setGroceryList($list)->setProduct($pain)->setStore($lidl)->setSource(GroceryItemSource::Manual)->setPosition(9));
        $em->flush();
        $em->clear();

        $data = $this->call('GET', '/api/grocery_lists/'.$this->id('grocery_list'));

        $aisles = [];
        foreach ($data['items'] as $item) {
            $aisles[$item['label'].' @ '.$item['store']['name']] = $item['aisle']['name'] ?? null;
        }
        self::assertSame('Frais', $aisles['Lait @ Lidl']);
        self::assertSame('Boulangerie', $aisles['Pain @ Carrefour'], 'The aisle is the one of the line\'s store, not of another');
        self::assertNull($aisles['Pain @ Lidl'], 'Pain has an aisle at Carrefour only: « Sans rayon » at Lidl');
    }
}
