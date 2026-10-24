<?php

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\Utensil;
use Maggie\Cookbook\Mcp\Tool\CreateRecipeTool;
use Maggie\Cookbook\Mcp\Tool\GetRecipeTool;
use Maggie\Cookbook\Mcp\Tool\ManageUtensilsTool;
use Maggie\Cookbook\Mcp\Tool\UpdateRecipeTool;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** `manage_utensils`, and the utensils of the recipe tools (MAG-489). */
class UtensilToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
    }

    private function load(string $fixtures = 'utensil.yaml'): void
    {
        $this->loadFixtures($fixtures);
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function utensils(mixed ...$args): array
    {
        return json_decode(self::getContainer()->get(ManageUtensilsTool::class)(...$args), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function call(string $tool, mixed ...$args): array
    {
        return json_decode(self::getContainer()->get($tool)(...$args), true, 512, JSON_THROW_ON_ERROR);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
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

    /** @return list<string> */
    private function utensilsOf(string $recipeId): array
    {
        $recipe = $this->em()->find(Recipe::class, $recipeId);
        self::assertNotNull($recipe);

        return array_map(static fn (Utensil $u) => $u->getName(), $recipe->getUtensils());
    }

    public function testEveryActionRequiresAUser(): void
    {
        $this->loadFixtures('utensil.yaml');

        self::assertArrayHasKey('error', $this->utensils('list'));
        self::assertArrayHasKey('error', $this->utensils('create', name: 'Wok'));
        self::assertSame(['Poêle', 'Thermomix'], $this->catalogueOf('test_user'));
    }

    public function testListAnswersMyUtensilsByName(): void
    {
        $this->load();

        $data = $this->utensils('list');

        self::assertSame(['Poêle', 'Thermomix'], array_column($data['utensils'], 'name'));
    }

    public function testCreatePersistsPublishesAndIndexes(): void
    {
        $this->load();

        $data = $this->utensils('create', name: ' Mandoline ');

        self::assertTrue($data['success']);
        self::assertSame('Mandoline', $data['utensil']['name']);
        self::assertSame(['Mandoline', 'Poêle', 'Thermomix'], $this->catalogueOf('test_user'));
        $this->assertMercureUpdatePublished('/api/utensils/'.$data['utensil']['id']);
        $this->assertElasticsearchIndexDispatched(Utensil::class);
    }

    public function testCreatingThermomixThenthermomixKeepsOneUtensil(): void
    {
        $this->load('user.yaml');

        $first = $this->utensils('create', name: 'Thermomix');
        $second = $this->utensils('create', name: 'thermomix');

        self::assertSame($first['utensil']['id'], $second['utensil']['id']);
        self::assertSame(['Thermomix'], $this->catalogueOf('test_user'));
    }

    public function testBadInputAnswersAnError(): void
    {
        $this->load();

        self::assertArrayHasKey('error', $this->utensils('create'));
        self::assertArrayHasKey('error', $this->utensils('create', name: '  '));
        self::assertArrayHasKey('error', $this->utensils('rename', name: 'Wok'));
        self::assertArrayHasKey('error', $this->utensils('delete'));
        self::assertArrayHasKey('error', $this->utensils('sharpen'));
        self::assertSame(['Poêle', 'Thermomix'], $this->catalogueOf('test_user'));
    }

    public function testRenameUpdatesPublishesAndIndexes(): void
    {
        $this->load();
        $pan = $this->getFixture('pan');

        $data = $this->utensils('rename', utensilId: (string) $pan->getId(), name: 'Poêle à crêpes');

        self::assertTrue($data['success']);
        self::assertSame(['Poêle à crêpes', 'Thermomix'], $this->catalogueOf('test_user'));
        $this->assertMercureUpdatePublished('/api/utensils/'.$pan->getId());
        $this->assertMercureUpdatePublished('/api/recipes/'.$this->getFixture('crepes')->getId());
        $this->assertElasticsearchIndexDispatched(Utensil::class);
    }

    public function testRenameToAnotherUtensilsNameIsRefused(): void
    {
        $this->load();

        $data = $this->utensils('rename', utensilId: (string) $this->getFixture('pan')->getId(), name: 'THERMOMIX');

        self::assertArrayHasKey('error', $data);
        self::assertSame(['Poêle', 'Thermomix'], $this->catalogueOf('test_user'));
    }

    public function testAnotherUsersUtensilCannotBeRenamedNorDeleted(): void
    {
        $this->load();
        $oven = (string) $this->getFixture('other_oven')->getId();

        self::assertArrayHasKey('error', $this->utensils('rename', utensilId: $oven, name: 'Mon four'));
        self::assertArrayHasKey('error', $this->utensils('delete', utensilId: $oven));
        self::assertSame(['Four'], $this->catalogueOf('other_user'));
    }

    public function testDeleteRemovesItFromItsRecipesWhichStay(): void
    {
        $this->load();
        $pan = (string) $this->getFixture('pan')->getId();
        $crepes = (string) $this->getFixture('crepes')->getId();

        $data = $this->utensils('delete', utensilId: $pan);

        self::assertTrue($data['success']);
        self::assertSame(['Thermomix'], $this->catalogueOf('test_user'));
        self::assertSame(['Thermomix'], $this->utensilsOf($crepes));
        $this->assertMercureDeletePublished('/api/utensils/'.$pan);
        $this->assertMercureUpdatePublished('/api/recipes/'.$crepes);
        $this->assertElasticsearchDeleteDispatched('utensils');
        $this->assertElasticsearchIndexDispatchedFor(Recipe::class, $crepes);
    }

    public function testCreateRecipeWithUtensilNamesCreatesAndLinksThem(): void
    {
        $this->load('user.yaml');

        $data = $this->call(CreateRecipeTool::class, 'Omelette', utensils: '["Casserole", "Poêle"]', steps: '[{"text":"Battre les oeufs"}]');

        self::assertTrue($data['success']);
        self::assertSame(['Casserole', 'Poêle'], array_column($data['recipe']['utensils'], 'name'));
        self::assertSame(['Casserole', 'Poêle'], $this->utensilsOf($data['recipe']['id']));
        self::assertSame(['Casserole', 'Poêle'], $this->catalogueOf('test_user'));
        $this->assertMercureUpdatePublished('/api/recipes/'.$data['recipe']['id']);
        $this->assertElasticsearchIndexDispatchedFor(Recipe::class, $data['recipe']['id']);
    }

    public function testCreateRecipeRefusesUtensilsThatAreNotAJsonArray(): void
    {
        $this->load('user.yaml');

        self::assertArrayHasKey('error', $this->call(CreateRecipeTool::class, 'Omelette', utensils: 'Poêle', steps: '[{"text":"Battre les oeufs"}]'));
        self::assertArrayHasKey('error', $this->call(CreateRecipeTool::class, 'Omelette', utensils: '{"name": "Poêle"}', steps: '[{"text":"Battre les oeufs"}]'));
        self::assertSame([], $this->em()->getRepository(Recipe::class)->findAll());
    }

    public function testUpdateRecipeReplacesTheListByIdOrName(): void
    {
        $this->load();
        $crepes = (string) $this->getFixture('crepes')->getId();
        $thermomix = (string) $this->getFixture('thermomix')->getId();

        $data = $this->call(UpdateRecipeTool::class, $crepes, utensils: json_encode([$thermomix, 'Louche'], JSON_THROW_ON_ERROR));

        self::assertTrue($data['success']);
        self::assertSame(['Louche', 'Thermomix'], array_column($data['recipe']['utensils'], 'name'));
        self::assertSame(['Louche', 'Thermomix'], $this->utensilsOf($crepes));
        self::assertSame(['Louche', 'Poêle', 'Thermomix'], $this->catalogueOf('test_user'));
        $this->assertMercureUpdatePublished('/api/recipes/'.$crepes);
        $this->assertElasticsearchIndexDispatchedFor(Recipe::class, $crepes);
    }

    public function testUpdateRecipeCannotLinkAnotherUsersUtensil(): void
    {
        $this->load();
        $crepes = (string) $this->getFixture('crepes')->getId();

        $data = $this->call(UpdateRecipeTool::class, $crepes, utensils: json_encode([(string) $this->getFixture('other_oven')->getId()], JSON_THROW_ON_ERROR));

        self::assertArrayHasKey('error', $data);
        self::assertSame(['Poêle', 'Thermomix'], $this->utensilsOf($crepes));
    }

    public function testUpdateRecipeCannotTouchAnotherUsersRecipe(): void
    {
        $this->load();
        $theirs = (string) $this->getFixture('other_recipe')->getId();

        $data = $this->call(UpdateRecipeTool::class, $theirs, name: 'Volée', utensils: '["Pied-de-biche"]');

        self::assertArrayHasKey('error', $data);
        self::assertSame(['Four'], $this->utensilsOf($theirs));
        self::assertSame(['Four'], $this->catalogueOf('other_user'));
        self::assertSame("Tarte de l'autre", $this->em()->find(Recipe::class, $theirs)?->getName());
    }

    public function testUpdateRecipeRequiresAUser(): void
    {
        $this->loadFixtures('utensil.yaml');
        $crepes = (string) $this->getFixture('crepes')->getId();

        $data = $this->call(UpdateRecipeTool::class, $crepes, utensils: '["Louche"]');

        self::assertArrayHasKey('error', $data);
        self::assertSame(['Poêle', 'Thermomix'], $this->utensilsOf($crepes));
    }

    public function testAUtensilNameOver255CharactersIsRefused(): void
    {
        $this->load();

        self::assertArrayHasKey('error', $this->utensils('create', name: str_repeat('a', 256)));
        self::assertArrayHasKey('error', $this->utensils('rename', utensilId: (string) $this->getFixture('pan')->getId(), name: str_repeat('a', 256)));
        self::assertSame(['Poêle', 'Thermomix'], $this->catalogueOf('test_user'));
    }

    public function testGetRecipeAnswersItsUtensils(): void
    {
        $this->load();
        $crepes = (string) $this->getFixture('crepes')->getId();
        $this->em();

        $data = $this->call(GetRecipeTool::class, $crepes);

        self::assertSame([
            ['id' => (string) $this->getFixture('pan')->getId(), 'name' => 'Poêle'],
            ['id' => (string) $this->getFixture('thermomix')->getId(), 'name' => 'Thermomix'],
        ], $data['recipe']['utensils']);
    }
}
