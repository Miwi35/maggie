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
        $result = $tool('Salade niçoise', 2, 'salad,summer', 'Best served cold', steps: '[{"text":"Assembler et servir frais"}]');

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

    public function testCreateRecipeWithNeitherIngredientsNorStepsCreatesNothingAndSaysWhatIsMissing(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(CreateRecipeTool::class);

        foreach ([
            ['Boeuf bourguignon : la vraie recette'],
            ['Boeuf bourguignon : la vraie recette', 4, 'plat', 'vu sur Marmiton'],
            ['Boeuf bourguignon : la vraie recette', 4, '', null, '[]', null, '[]'],
        ] as $arguments) {
            $data = json_decode($tool(...$arguments), true, 512, JSON_THROW_ON_ERROR);

            self::assertArrayNotHasKey('success', $data);
            self::assertStringContainsString('ingredients', $data['error']);
            self::assertStringContainsString('steps', $data['error']);
            self::assertStringContainsString('Boeuf bourguignon : la vraie recette', $data['error']);
        }

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(0, $em->getRepository(Recipe::class)->findAll());
        $this->assertMercureUpdateCount(0);
    }

    public function testCreateRecipeWithOnlyStepsIsStillCreated(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(CreateRecipeTool::class);

        $data = json_decode($tool('Tartines', steps: '[{"text":"Griller le pain"}]'), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
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

    public function testGetRecipeScalesTheQuantitiesToTheGuestsAndStoresNothing(): void
    {
        // 500 g for 4 is 750 g for 6 (MAG-482).
        $this->loadFixtures('recipe.yaml');
        $this->loginFixtureUser();
        $recipeId = (string) $this->getFixture('pasta')->getId();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        $data = json_decode(self::getContainer()->get(GetRecipeTool::class)($recipeId, guests: 6), true, 512, JSON_THROW_ON_ERROR);

        self::assertEquals(750, $data['recipe']['ingredients'][0]['quantity']);
        self::assertSame(6, $data['recipe']['scaledFor']);
        self::assertEquals(1.5, $data['recipe']['scaleFactor']);
        self::assertSame(4, $data['recipe']['servings']);

        $em->clear();
        $stored = $em->find(Recipe::class, $recipeId);
        self::assertSame(500.0, $stored?->getIngredients()->first()->getQuantity());
        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Recipe::class);
    }

    public function testGetRecipeRefusesGuestsBelowOne(): void
    {
        $this->loadFixtures('recipe.yaml');
        $this->loginFixtureUser();

        $data = json_decode(self::getContainer()->get(GetRecipeTool::class)((string) $this->getFixture('pasta')->getId(), guests: 0), true, 512, JSON_THROW_ON_ERROR);

        self::assertStringContainsString('at least 1', $data['error']);
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

    /**
     * MAG-114 § 2, which had no test of its own: `searchByTags` ran a `LIKE`
     * against a JSON column, which Postgres refuses outright — so asking for a
     * tag did not come back empty, it *failed*. Only the by-name search was
     * covered, and the two take different code paths.
     *
     * A browser journey cannot assert this: the fake model never reads a tool
     * result, so all AG-UI carries is whether the round succeeded
     * (`recipes-ciqual.spec.ts` asserts that, and nothing more). Whether the
     * right recipes come back has to be asserted here.
     */
    public function testSearchRecipesByTag(): void
    {
        $this->loadFixtures('recipe.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(SearchRecipesTool::class);
        $data = json_decode($tool(tag: 'italian'), true, 512, JSON_THROW_ON_ERROR);

        self::assertCount(1, $data['recipes']);
        self::assertSame('Pâtes à la tomate', $data['recipes'][0]['name']);
        self::assertSame(['pasta', 'italian'], $data['recipes'][0]['tags']);
    }

    public function testSearchRecipesByATagNobodyUsedComesBackEmpty(): void
    {
        // The control: without it, a tag search returning *everything* would
        // pass the test above.
        $this->loadFixtures('recipe.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(SearchRecipesTool::class);
        $data = json_decode($tool(tag: 'végétarien'), true, 512, JSON_THROW_ON_ERROR);

        self::assertCount(0, $data['recipes']);
    }

    public function testSearchRecipesWithNeitherQueryNorTagSaysSo(): void
    {
        $this->loadFixtures('recipe.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(SearchRecipesTool::class);
        $data = json_decode($tool(), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
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

    public function testMaggieChangingAnIngredientQuantityReachesTheOpenRecipe(): void
    {
        $this->loadFixtures('recipe.yaml');
        $this->loginFixtureUser();

        $recipe = $this->getFixture('pasta');
        $tomato = $this->getFixture('tomato');

        $tool = self::getContainer()->get(UpdateRecipeTool::class);
        $tool((string) $recipe->getId(), ingredients: json_encode([
            ['ingredientId' => (string) $tomato->getId(), 'quantity' => 750, 'unit' => 'g'],
        ], JSON_THROW_ON_ERROR));

        $published = null;
        foreach ($this->getMercureHub()->getUpdates() as $update) {
            if (str_ends_with($update->getTopics()[0], '/api/recipes/'.$recipe->getId())) {
                $published = json_decode($update->getData(), true, 512, JSON_THROW_ON_ERROR);
            }
        }
        self::assertNotNull($published, 'No Mercure update published for the recipe.');
        self::assertSame('Tomate', $published['ingredients'][0]['ingredientName']);
        self::assertEquals(750, $published['ingredients'][0]['quantity']);
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
        self::assertSame(0, $data['deletedMeals']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Recipe::class, $recipe->getId()));

        $this->assertMercureUpdatePublished('/recipes/');
        $this->assertElasticsearchDeleteDispatched('recipes');
    }

    public function testUpdateRecipeClearEmptiesNotes(): void
    {
        $this->loadFixtures('recipe_notes.yaml');
        $this->loginFixtureUser();

        $recipe = $this->getFixture('pasta');

        $tool = self::getContainer()->get(UpdateRecipeTool::class);
        $data = json_decode($tool((string) $recipe->getId(), clear: ['notes', 'name']), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(Recipe::class, $recipe->getId());
        self::assertNull($stored->getNotes());
        self::assertSame('Pâtes à la tomate', $stored->getName(), 'Required fields cannot be cleared');
        self::assertSame(['pasta', 'italian'], $stored->getTags());

        $this->assertMercureUpdatePublished('/recipes/');
        $this->assertElasticsearchIndexDispatched(Recipe::class);
    }

    public function testUpdateRecipeWithEmptyTagsEmptiesTheTags(): void
    {
        $this->loadFixtures('recipe_notes.yaml');
        $this->loginFixtureUser();

        $recipe = $this->getFixture('pasta');

        $tool = self::getContainer()->get(UpdateRecipeTool::class);
        $data = json_decode($tool((string) $recipe->getId(), tags: ''), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame([], $data['recipe']['tags']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(Recipe::class, $recipe->getId());
        self::assertSame([], $stored->getTags());
        self::assertSame('Ajouter du basilic', $stored->getNotes());
    }

    public function testUpdateUnknownRecipeReturnsAnError(): void
    {
        $this->loadFixtures('recipe_notes.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(UpdateRecipeTool::class);
        $data = json_decode($tool('01ARZ3NDEKTSV4RRFFQ69G5FAV', clear: ['notes']), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }
}
