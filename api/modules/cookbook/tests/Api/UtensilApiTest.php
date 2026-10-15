<?php

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\Utensil;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The catalogue of utensils, per user (MAG-489). */
class UtensilApiTest extends WebTestCase
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
        $this->loadFixtures('UtensilApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @param array<string, mixed>|null $payload */
    private function send(string $method, string $uri, ?array $payload = null, bool $authenticated = true): void
    {
        $this->client->request($method, $uri, [], [], array_merge([
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $authenticated ? $this->authHeaders() : []), null === $payload ? null : json_encode($payload, JSON_THROW_ON_ERROR));
    }

    /** @return array<string, mixed> */
    private function json(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    /** @return list<string> the names of the user's utensils, sorted */
    private function catalogueOf(string $userRef): array
    {
        /** @var User $user */
        $user = $this->getFixture($userRef);
        $names = array_map(
            static fn (Utensil $u) => $u->getName(),
            $this->em()->getRepository(Utensil::class)->findBy(['user' => $user->getId()]),
        );
        sort($names);

        return $names;
    }

    public function testTheCatalogueRequiresAuthentication(): void
    {
        $this->loadFixtures('UtensilApiTest.yaml');

        $this->send('GET', '/api/utensils', authenticated: false);
        self::assertResponseStatusCodeSame(401);

        $this->send('POST', '/api/utensils', ['name' => 'Wok'], authenticated: false);
        self::assertResponseStatusCodeSame(401);
        self::assertSame(['Poêle', 'Thermomix'], $this->catalogueOf('test_user'));
    }

    public function testTheCatalogueListsMyUtensilsOnly(): void
    {
        $this->load();

        $this->send('GET', '/api/utensils?order[name]=asc');

        self::assertResponseIsSuccessful();
        self::assertSame(['Poêle', 'Thermomix'], array_column($this->json()['member'], 'name'));
    }

    public function testAnotherUsersUtensilIsNotFound(): void
    {
        $this->load();
        $oven = $this->getFixture('other_oven');

        $this->send('GET', '/api/utensils/'.$oven->getId());
        self::assertResponseStatusCodeSame(404);

        $this->send('PATCH', '/api/utensils/'.$oven->getId(), ['name' => 'Mon four']);
        self::assertResponseStatusCodeSame(404);

        $this->send('DELETE', '/api/utensils/'.$oven->getId());
        self::assertResponseStatusCodeSame(404);
        self::assertSame(['Four'], $this->catalogueOf('other_user'));
    }

    public function testCreatingAUtensilStoresItWithoutTheSpacesAroundItsName(): void
    {
        $this->load();

        $this->send('POST', '/api/utensils', ['name' => '  Mandoline ']);

        self::assertResponseStatusCodeSame(201);
        self::assertSame('Mandoline', $this->json()['name']);
        self::assertSame(['Mandoline', 'Poêle', 'Thermomix'], $this->catalogueOf('test_user'));
        $this->assertMercureUpdatePublished('/api/utensils/'.$this->json()['id']);
        $this->assertElasticsearchIndexDispatched(Utensil::class);
    }

    public function testCreatingANameAlreadyThereInAnotherCaseKeepsOneUtensil(): void
    {
        $this->load();
        $thermomix = $this->getFixture('thermomix');

        $this->send('POST', '/api/utensils', ['name' => ' thermomix ']);

        self::assertResponseIsSuccessful();
        self::assertSame((string) $thermomix->getId(), $this->json()['id']);
        self::assertSame('Thermomix', $this->json()['name']);
        self::assertSame(['Poêle', 'Thermomix'], $this->catalogueOf('test_user'));
    }

    public function testTheSameNameIsFreeForAnotherUser(): void
    {
        $this->load();

        $this->send('POST', '/api/utensils', ['name' => 'Four']);

        self::assertResponseStatusCodeSame(201);
        self::assertSame(['Four', 'Poêle', 'Thermomix'], $this->catalogueOf('test_user'));
        self::assertSame(['Four'], $this->catalogueOf('other_user'));
    }

    public function testABlankNameIsRefused(): void
    {
        $this->load();

        $this->send('POST', '/api/utensils', ['name' => '   ']);

        self::assertResponseStatusCodeSame(422);
        self::assertSame(['Poêle', 'Thermomix'], $this->catalogueOf('test_user'));
    }

    public function testRenamingAUtensilRepublishesTheRecipesMadeWithIt(): void
    {
        $this->load();
        $pan = $this->getFixture('pan');
        $crepes = $this->getFixture('crepes');

        $this->send('PATCH', '/api/utensils/'.$pan->getId(), ['name' => 'Poêle à crêpes']);

        self::assertResponseIsSuccessful();
        self::assertSame(['Poêle à crêpes', 'Thermomix'], $this->catalogueOf('test_user'));
        $this->assertMercureUpdatePublished('/api/utensils/'.$pan->getId());
        $this->assertElasticsearchIndexDispatched(Utensil::class);

        $sheet = $this->mercurePayloadsOn('/api/recipes/'.$crepes->getId());
        self::assertCount(1, $sheet, 'the open recipe sheet hears of the new name');
        self::assertSame(['Poêle à crêpes', 'Thermomix'], array_column($sheet[0]['utensils'], 'name'));
    }

    public function testRenamingAUtensilInAnotherCaseOfItsOwnNameIsAllowed(): void
    {
        $this->load();
        $pan = $this->getFixture('pan');

        $this->send('PATCH', '/api/utensils/'.$pan->getId(), ['name' => 'POÊLE']);

        self::assertResponseIsSuccessful();
        self::assertSame(['POÊLE', 'Thermomix'], $this->catalogueOf('test_user'));
    }

    public function testRenamingAUtensilToAnotherOnesNameIsAConflict(): void
    {
        $this->load();
        $pan = $this->getFixture('pan');

        $this->send('PATCH', '/api/utensils/'.$pan->getId(), ['name' => 'thermomix']);

        self::assertResponseStatusCodeSame(409);
        self::assertSame(['Poêle', 'Thermomix'], $this->catalogueOf('test_user'));
    }

    public function testDeletingAUtensilRemovesItFromItsRecipesWhichStay(): void
    {
        $this->load();
        $pan = $this->getFixture('pan');
        $crepesId = (string) $this->getFixture('crepes')->getId();

        $this->send('DELETE', '/api/utensils/'.$pan->getId());

        self::assertResponseStatusCodeSame(204);
        self::assertSame(['Thermomix'], $this->catalogueOf('test_user'));
        $crepes = $this->em()->find(Recipe::class, $crepesId);
        self::assertNotNull($crepes, 'the recipe stays');
        self::assertSame(['Thermomix'], array_map(static fn (Utensil $u) => $u->getName(), $crepes->getUtensils()));

        $this->assertMercureDeletePublished('/api/utensils/'.$pan->getId());
        $this->assertMercureUpdatePublished('/api/recipes/'.$crepesId);
        $this->assertElasticsearchDeleteDispatched('utensils');
        $this->assertElasticsearchIndexDispatchedFor(Recipe::class, $crepesId);
    }

    public function testDeletingRequiresAuthentication(): void
    {
        $this->loadFixtures('UtensilApiTest.yaml');

        $this->send('DELETE', '/api/utensils/'.$this->getFixture('pan')->getId(), authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertSame(['Poêle', 'Thermomix'], $this->catalogueOf('test_user'));
    }
}
