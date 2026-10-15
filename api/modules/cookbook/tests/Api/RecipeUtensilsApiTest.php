<?php

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Elastic\Elasticsearch\Client;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\Utensil;
use Maggie\Core\Entity\User;
use Maggie\Core\Tests\Elasticsearch\RecordingElasticsearchTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** What a recipe is made with, read and written with the recipe (MAG-489). */
class RecipeUtensilsApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use RecordingElasticsearchTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function load(string $fixtures = 'UtensilApiTest.yaml'): void
    {
        $this->loadFixtures($fixtures);
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @param array<string, mixed> $payload */
    private function send(string $method, string $uri, array $payload, bool $authenticated = true): void
    {
        $this->client->request($method, $uri, [], [], array_merge([
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $authenticated ? $this->authHeaders() : []), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    /** @return array<string, mixed> */
    private function get(string $uri): array
    {
        $this->client->request('GET', $uri, [], [], array_merge(['HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()));
        self::assertResponseIsSuccessful();

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    /** @return list<string> the names of the recipe's utensils, as stored */
    private function utensilsOf(string $recipeId): array
    {
        $recipe = $this->em()->find(Recipe::class, $recipeId);
        self::assertNotNull($recipe);

        return array_map(static fn (Utensil $u) => $u->getName(), $recipe->getUtensils());
    }

    /** @return list<string> */
    private function catalogueOf(string $userRef): array
    {
        /** @var User $user */
        $user = $this->getFixture($userRef);
        $names = array_map(static fn (Utensil $u) => $u->getName(), $this->em()->getRepository(Utensil::class)->findBy(['user' => $user->getId()]));
        sort($names);

        return $names;
    }

    private function patchCrepes(mixed $utensils): string
    {
        $id = (string) $this->getFixture('crepes')->getId();
        $this->send('PATCH', '/api/recipes/'.$id, ['utensils' => $utensils]);

        return $id;
    }

    public function testARecipeCreatedWithUtensilNamesCreatesThemAndLinksThem(): void
    {
        $this->load('RecipeApiTest.yaml');

        $this->send('POST', '/api/recipes', ['name' => 'Omelette', 'utensils' => ['Casserole', 'Poêle']]);

        self::assertResponseStatusCodeSame(201);
        $created = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(['Casserole', 'Poêle'], array_column($created['utensils'], 'name'));
        self::assertSame(['Casserole', 'Poêle'], $this->utensilsOf($created['id']));
        self::assertSame(['Casserole', 'Poêle'], $this->catalogueOf('test_user'));
        $this->assertMercureUpdatePublished('/api/recipes/'.$created['id']);
        $this->assertMercureUpdatePublished('/api/utensils/');
        $this->assertElasticsearchIndexDispatchedFor(Recipe::class, $created['id']);
        $this->assertElasticsearchIndexDispatched(Utensil::class);
    }

    public function testCreatingARecipeWithUtensilsRequiresAuthentication(): void
    {
        $this->loadFixtures('RecipeApiTest.yaml');

        $this->send('POST', '/api/recipes', ['name' => 'Omelette', 'utensils' => ['Casserole']], authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertSame([], $this->catalogueOf('test_user'));
    }

    public function testANameAlreadyInTheCatalogueIsLinkedNotCreatedAgain(): void
    {
        $this->load();

        $this->send('POST', '/api/recipes', ['name' => 'Galettes', 'utensils' => [' poêle', 'Wok', 'wok']]);

        self::assertResponseStatusCodeSame(201);
        $id = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR)['id'];
        self::assertSame(['Poêle', 'Wok'], $this->utensilsOf($id));
        self::assertSame(['Poêle', 'Thermomix', 'Wok'], $this->catalogueOf('test_user'));
    }

    public function testTheRecipeReadListsItsUtensilsByName(): void
    {
        $this->load();
        $crepes = $this->getFixture('crepes');

        $read = $this->get('/api/recipes/'.$crepes->getId());

        self::assertSame(['Poêle', 'Thermomix'], array_column($read['utensils'], 'name'));
        self::assertSame((string) $this->getFixture('pan')->getId(), $read['utensils'][0]['id']);
        self::assertSame('/api/utensils/'.$this->getFixture('pan')->getId(), $read['utensils'][0]['@id']);
    }

    public function testTheRecipeCollectionListsTheUtensilsOfEachRecipe(): void
    {
        $this->load();

        $byName = array_column($this->get('/api/recipes')['member'], 'utensils', 'name');

        self::assertSame(['Poêle', 'Thermomix'], array_column($byName['Crêpes'], 'name'));
        self::assertSame([], $byName['Soupe']);
    }

    /**
     * The real read goes through Elasticsearch, whose document does not hold the
     * utensils: they must still come back, read from the database.
     */
    public function testTheRecipeServedFromElasticsearchStillListsItsUtensils(): void
    {
        $this->load();
        /** @var Recipe $crepes */
        $crepes = $this->getFixture('crepes');
        $document = json_decode(json_encode($crepes->toSearchDocument(), JSON_THROW_ON_ERROR), true, 512, JSON_THROW_ON_ERROR);
        $id = (string) $crepes->getId();
        $this->client->disableReboot();
        self::getContainer()->set(Client::class, $this->recordingClient(static fn (string $method, string $path): array => str_ends_with($path, '/_doc/'.$id)
            ? [200, ['_index' => 'recipes', '_id' => $id, 'found' => true, '_source' => $document]]
            : [404, ['found' => false]]));
        $this->em();

        $read = $this->get('/api/recipes/'.$id);

        self::assertNotEmpty($this->requests, 'the read went to Elasticsearch');
        self::assertSame(['Poêle', 'Thermomix'], array_column($read['utensils'], 'name'));
    }

    public function testPatchReplacesTheWholeListByIdIriOrName(): void
    {
        $this->load();
        $thermomix = $this->getFixture('thermomix');

        $id = $this->patchCrepes([['@id' => '/api/utensils/'.$thermomix->getId()], 'Mandoline']);

        self::assertResponseIsSuccessful();
        self::assertSame(['Mandoline', 'Thermomix'], $this->utensilsOf($id));
        self::assertSame(['Mandoline', 'Poêle', 'Thermomix'], $this->catalogueOf('test_user'), 'the utensil left out stays in the catalogue');
        $payloads = $this->mercurePayloadsOn('/api/recipes/'.$id);
        self::assertNotEmpty($payloads);
        self::assertSame(['Mandoline', 'Thermomix'], array_column(end($payloads)['utensils'], 'name'));
        $this->assertElasticsearchIndexDispatchedFor(Recipe::class, $id);
    }

    public function testPatchAcceptsTheUtensilsAsTheApiServesThem(): void
    {
        $this->load();
        $crepes = $this->getFixture('crepes');
        $read = $this->get('/api/recipes/'.$crepes->getId());

        $this->patchCrepes(array_reverse($read['utensils']));

        self::assertResponseIsSuccessful();
        self::assertSame(['Poêle', 'Thermomix'], $this->utensilsOf((string) $crepes->getId()));
        self::assertSame(['Poêle', 'Thermomix'], $this->catalogueOf('test_user'));
    }

    public function testPatchWithoutUtensilsKeepsThem(): void
    {
        $this->load();
        $id = (string) $this->getFixture('crepes')->getId();

        $this->send('PATCH', '/api/recipes/'.$id, ['name' => 'Crêpes de froment']);

        self::assertResponseIsSuccessful();
        self::assertSame(['Poêle', 'Thermomix'], $this->utensilsOf($id));
    }

    public function testAnEmptyListUnlinksEveryUtensil(): void
    {
        $this->load();

        $id = $this->patchCrepes([]);

        self::assertResponseIsSuccessful();
        self::assertSame([], $this->utensilsOf($id));
        self::assertSame(['Poêle', 'Thermomix'], $this->catalogueOf('test_user'));
    }

    public function testAnotherUsersUtensilCannotBeLinked(): void
    {
        $this->load();
        $oven = $this->getFixture('other_oven');

        $id = $this->patchCrepes([(string) $oven->getId()]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(['Poêle', 'Thermomix'], $this->utensilsOf($id));
    }

    /** @return iterable<string, array{mixed}> */
    public static function notAListOfUtensils(): iterable
    {
        yield 'a string' => ['Poêle'];
        yield 'a number in the list' => [[42]];
        yield 'a blank name' => [['  ']];
        yield 'an object without id nor name' => [[['label' => 'Poêle']]];
    }

    #[\PHPUnit\Framework\Attributes\DataProvider('notAListOfUtensils')]
    public function testUtensilsThatAreNotAListOfIdsOrNamesAreRefused(mixed $utensils): void
    {
        $this->load();

        $id = $this->patchCrepes($utensils);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(['Poêle', 'Thermomix'], $this->utensilsOf($id));
    }
}
