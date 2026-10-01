<?php

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Mcp\Tool\GenerateGroceryListTool;
use Maggie\Cookbook\Mcp\Tool\GetRecipeTool;
use Maggie\Cookbook\Mcp\Tool\ManageMealsTool;
use Maggie\Cookbook\Mcp\Tool\SearchIngredientsTool;
use Maggie\Cookbook\Mcp\Tool\SearchRecipesTool;
use Maggie\Core\Entity\User;
use Maggie\Core\Mcp\MissingMcpUserException;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * Two users share the database: every cookbook tool must only see its caller's data.
 */
class UserIsolationToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    /** @var array<string, string> */
    private array $ids = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    /** Loads the two-user fixture; returns fresh (managed) entities after clearing the identity map. */
    private function load(): void
    {
        $this->loadFixtures('isolation.yaml');
        foreach (['test_user', 'other_user', 'own_recipe', 'other_recipe', 'other_repas_agenda'] as $ref) {
            $this->ids[$ref] = (string) $this->getFixture($ref)->getId();
        }
        $this->em()->clear();
    }

    /** Acts as test_user with a managed User entity. */
    private function loadAndLogin(): User
    {
        $this->load();

        $user = $this->em()->find(User::class, $this->ids['test_user']);
        $this->loginUser($user);

        return $user;
    }

    /** @return array<string, mixed> */
    private function decode(string $json): array
    {
        return json_decode($json, true, 512, JSON_THROW_ON_ERROR);
    }

    public function testGetRecipeReturnsTheCallersRecipe(): void
    {
        $this->loadAndLogin();

        $data = $this->decode((self::getContainer()->get(GetRecipeTool::class))($this->ids['own_recipe']));

        self::assertSame('Ma tarte', $data['recipe']['name']);
        self::assertSame(['Tomate'], array_column($data['recipe']['ingredients'], 'ingredient'));
    }

    public function testGetRecipeRefusesAnotherUsersRecipe(): void
    {
        $this->loadAndLogin();

        $data = $this->decode((self::getContainer()->get(GetRecipeTool::class))($this->ids['other_recipe']));

        self::assertArrayHasKey('error', $data);
        self::assertArrayNotHasKey('recipe', $data);
        self::assertStringNotContainsString("Tarte de l'autre", json_encode($data, JSON_THROW_ON_ERROR));
    }

    public function testGetRecipeWithoutUserIsRefused(): void
    {
        $this->load();

        $data = $this->decode((self::getContainer()->get(GetRecipeTool::class))($this->ids['own_recipe']));

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertArrayNotHasKey('recipe', $data);
    }

    public function testSearchRecipesByNameOnlyReturnsTheCallersRecipes(): void
    {
        $this->loadAndLogin();

        $data = $this->decode((self::getContainer()->get(SearchRecipesTool::class))(query: 'tarte'));

        self::assertSame(['Ma tarte'], array_column($data['recipes'], 'name'));
    }

    public function testSearchRecipesByTagOnlyReturnsTheCallersRecipes(): void
    {
        $this->loadAndLogin();

        $data = $this->decode((self::getContainer()->get(SearchRecipesTool::class))(tag: 'dessert'));

        self::assertSame(['Ma tarte'], array_column($data['recipes'], 'name'));
    }

    public function testSearchRecipesWithoutUserIsRefused(): void
    {
        $this->load();

        $data = $this->decode((self::getContainer()->get(SearchRecipesTool::class))(query: 'tarte'));

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertArrayNotHasKey('recipes', $data);
    }

    public function testSearchIngredientsOnlyReturnsTheCallersIngredients(): void
    {
        $this->loadAndLogin();

        $data = $this->decode((self::getContainer()->get(SearchIngredientsTool::class))('Tom'));

        self::assertSame(['Tomate'], array_column($data['ingredients'], 'name'));
    }

    public function testSearchIngredientsWithoutUserIsRefused(): void
    {
        $this->load();

        $data = $this->decode((self::getContainer()->get(SearchIngredientsTool::class))('Tom'));

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertArrayNotHasKey('ingredients', $data);
    }

    public function testGenerateGroceryListOnlyUsesTheCallersMeals(): void
    {
        $this->loadAndLogin();

        $data = $this->decode((self::getContainer()->get(GenerateGroceryListTool::class))('2030-01-14', '2030-01-16'));

        self::assertTrue($data['success']);
        $labels = [];
        foreach ($data['groceryList']['storeGroups'] as $group) {
            foreach ($group['items'] as $item) {
                $labels[] = $item['label'];
            }
        }
        self::assertSame(['Tomate'], $labels);
    }

    public function testListMealsOnlyReturnsTheCallersMeals(): void
    {
        $this->loadAndLogin();

        $data = $this->decode((self::getContainer()->get(ManageMealsTool::class))('list', fromDate: '2030-01-14', toDate: '2030-01-16'));

        self::assertSame(1, $data['count']);
        self::assertSame('lunch', $data['meals'][0]['slot']);
    }

    public function testFirstMealCreatesTheRepasAgendaForTheCurrentUser(): void
    {
        $user = $this->loadAndLogin();

        $data = $this->decode((self::getContainer()->get(ManageMealsTool::class))('create', date: '2030-02-01', slot: 'lunch'));

        self::assertTrue($data['success'] ?? false, json_encode($data, JSON_THROW_ON_ERROR));

        $this->em()->clear();
        $meal = $this->em()->getRepository(Meal::class)->find($data['meal']['id']);
        self::assertNotNull($meal);
        self::assertSame((string) $user->getId(), (string) $meal->getAgenda()->getUser()->getId());
        self::assertNotSame($this->ids['other_user'], (string) $meal->getAgenda()->getUser()->getId());
        self::assertNotSame($this->ids['other_repas_agenda'], (string) $meal->getAgenda()->getId());

        $repasAgendas = $this->em()->getRepository(Agenda::class)->findBy(['name' => 'Repas']);
        self::assertCount(2, $repasAgendas, 'Each user gets their own "Repas" agenda');
    }

    public function testAutoCreatedRepasAgendaIsIndexedAndPublished(): void
    {
        $user = $this->loadAndLogin();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->decode((self::getContainer()->get(ManageMealsTool::class))('create', date: '2030-02-01', slot: 'lunch'));
        self::assertTrue($data['success'] ?? false, json_encode($data, JSON_THROW_ON_ERROR));

        $agenda = $this->em()->getRepository(Agenda::class)->findOneBy(['name' => 'Repas', 'user' => $user]);
        self::assertNotNull($agenda);

        $this->assertElasticsearchIndexDispatched(Agenda::class);
        $this->assertMercureUpdatePublished('/users/'.$user->getId().'/api/agendas/'.$agenda->getId());
    }

    public function testExistingRepasAgendaIsNotBroadcastAgain(): void
    {
        $this->loadAndLogin();
        $tool = self::getContainer()->get(ManageMealsTool::class);
        $tool('create', date: '2030-02-01', slot: 'lunch');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $tool('create', date: '2030-02-02', slot: 'lunch');

        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            $message = $envelope->getMessage();
            self::assertFalse(
                $message instanceof \Maggie\Core\Elasticsearch\Message\IndexDocumentCommand && Agenda::class === $message->entityClass,
                'The agenda already exists: it must not be reindexed.',
            );
        }
    }

    public function testMealCannotUseAnotherUsersRecipe(): void
    {
        $this->loadAndLogin();

        $data = $this->decode((self::getContainer()->get(ManageMealsTool::class))('create', date: '2030-02-01', slot: 'dinner', recipeIds: $this->ids['other_recipe']));

        self::assertStringContainsString('Recipe not found', $data['error'] ?? '');
        self::assertSame(2, $this->em()->getRepository(Meal::class)->count([]));
    }
}
