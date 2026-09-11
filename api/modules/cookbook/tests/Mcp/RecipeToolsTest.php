<?php

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Mcp\Tool\CreateRecipeTool;
use Maggie\Cookbook\Mcp\Tool\DeleteRecipeTool;
use Maggie\Cookbook\Mcp\Tool\GetRecipeTool;
use Maggie\Cookbook\Mcp\Tool\SearchRecipesTool;
use Maggie\Cookbook\Mcp\Tool\UpdateRecipeTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class RecipeToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testCreateRecipePersistsAndPublishes(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(CreateRecipeTool::class);
        $result = $tool('Salade niçoise', 2, 'salad,summer', 'Best served cold');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('Salade niçoise', $data['recipe']['name']);
        self::assertSame(2, $data['recipe']['servings']);
        self::assertContains('salad', $data['recipe']['tags']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $recipes = $em->getRepository(Recipe::class)->findAll();
        self::assertCount(1, $recipes);
        self::assertSame('Salade niçoise', $recipes[0]->getName());

        $this->assertMercureUpdatePublished('/recipes/');
        $this->assertElasticsearchIndexDispatched(Recipe::class);
    }

    public function testGetRecipeReturnsWithIngredients(): void
    {
        $this->loadFixtures('recipe.yaml');
        $this->loginFixtureUser();

        $recipe = $this->getFixture('pasta');
        $recipeId = (string) $recipe->getId();

        // Clear identity map so the tool loads fresh from DB with relations
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();

        $tool = self::getContainer()->get(GetRecipeTool::class);
        $result = $tool($recipeId);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('Pâtes à la tomate', $data['recipe']['name']);
        self::assertCount(1, $data['recipe']['ingredients']);
        self::assertSame('Tomate', $data['recipe']['ingredients'][0]['ingredient']);
    }

    public function testSearchRecipesByName(): void
    {
        $this->loadFixtures('recipe.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(SearchRecipesTool::class);
        $result = $tool(query: 'Pâtes');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(1, $data['recipes']);
        self::assertSame('Pâtes à la tomate', $data['recipes'][0]['name']);
    }

    public function testSearchRecipesReturnsEmptyForNoMatch(): void
    {
        $this->loadFixtures('recipe.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(SearchRecipesTool::class);
        $result = $tool(query: 'Nonexistent');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(0, $data['recipes']);
    }

    public function testUpdateRecipeUpdatesAndPublishes(): void
    {
        $this->loadFixtures('recipe.yaml');
        $this->loginFixtureUser();

        $recipe = $this->getFixture('pasta');

        $tool = self::getContainer()->get(UpdateRecipeTool::class);
        $result = $tool((string) $recipe->getId(), name: 'Pâtes bolognaise', servings: 6);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('Pâtes bolognaise', $data['recipe']['name']);
        self::assertSame(6, $data['recipe']['servings']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Recipe::class, $recipe->getId());
        self::assertSame('Pâtes bolognaise', $refreshed->getName());

        $this->assertMercureUpdatePublished('/recipes/');
        $this->assertElasticsearchIndexDispatched(Recipe::class);
    }

    public function testDeleteRecipeRemovesAndPublishes(): void
    {
        $this->loadFixtures('recipe.yaml');
        $this->loginFixtureUser();
        $this->loginUser($this->getFixture('test_user'));

        $recipe = $this->getFixture('pasta');

        $tool = self::getContainer()->get(DeleteRecipeTool::class);
        $result = $tool((string) $recipe->getId());

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Recipe::class, $recipe->getId()));

        $this->assertMercureUpdatePublished('/recipes/');
        $this->assertElasticsearchDeleteDispatched('recipes');
    }
}
