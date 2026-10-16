<?php

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\RecipeStep;
use Maggie\Cookbook\Mcp\Tool\CreateRecipeTool;
use Maggie\Cookbook\Mcp\Tool\GetRecipeTool;
use Maggie\Cookbook\Mcp\Tool\UpdateRecipeTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** The steps of a recipe in create_recipe, update_recipe and get_recipe (MAG-490). */
class RecipeStepToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    /** The world the API suite tests the steps against. */
    private const FIXTURES = __DIR__.'/../Api/fixtures/RecipeStepsTest.yaml';

    protected function setUp(): void
    {
        self::bootKernel();
    }

    private function load(): void
    {
        $this->loadFixtures(self::FIXTURES);
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();
        // Read from the database, as a real call would: the fixtures' recipes
        // do not hold the lines that point at them.
        $this->em();
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

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** @return list<array<string, mixed>> the steps as stored, without their ids */
    private function stepsOf(string $recipeId): array
    {
        $recipe = $this->em()->find(Recipe::class, $recipeId);
        self::assertNotNull($recipe);

        return array_map(static fn (RecipeStep $step) => [
            'position' => $step->getPosition(),
            'text' => $step->getText(),
            'durationMinutes' => $step->getDurationMinutes(),
            'background' => $step->isBackground(),
            'ingredients' => array_column($step->getIngredients(), 'name'),
            'utensils' => array_column($step->getUtensils(), 'name'),
            'waitsFor' => $step->getWaitsFor(),
        ], $recipe->getSteps());
    }

    /**
     * The reference example, written as Maggie would: ingredients by name.
     *
     * @return list<array<string, mixed>>
     */
    private static function referenceSteps(): array
    {
        return [
            ['text' => "Mettre l'eau à bouillir", 'durationMinutes' => 10, 'background' => true, 'utensils' => ['Casserole']],
            ['text' => 'Couper les légumes', 'ingredientIds' => ['courgette', 'Poivron']],
            ['text' => 'Cuire les pâtes', 'durationMinutes' => 12, 'background' => true, 'ingredientIds' => ['Pâtes'], 'utensils' => ['Casserole'], 'waitsFor' => [1]],
            ['text' => 'Faire revenir les légumes', 'durationMinutes' => 8, 'ingredientIds' => ['Courgette', 'Poivron'], 'utensils' => ['Poêle']],
            ['text' => 'Égoutter, mélanger', 'waitsFor' => [3, 4]],
        ];
    }

    private function referenceIngredients(): string
    {
        return json_encode([
            ['ingredientId' => $this->id('pasta_food'), 'quantity' => 200, 'unit' => 'g'],
            ['ingredientId' => $this->id('zucchini_food'), 'quantity' => 1, 'unit' => 'piece'],
            ['ingredientId' => $this->id('pepper_food'), 'quantity' => 1, 'unit' => 'piece'],
        ], JSON_THROW_ON_ERROR);
    }

    public function testCreateRecipeRequiresAUser(): void
    {
        $this->loadFixtures(self::FIXTURES);

        $data = $this->call(CreateRecipeTool::class, 'Pâtes', ingredients: $this->referenceIngredients(), steps: json_encode(self::referenceSteps(), JSON_THROW_ON_ERROR));

        self::assertArrayHasKey('error', $data);
        self::assertSame(2, $this->em()->getRepository(RecipeStep::class)->count([]));
    }

    public function testTheReferenceRecipeCreatedThroughMcpReadsBackIdentical(): void
    {
        $this->load();

        $data = $this->call(CreateRecipeTool::class, 'Pâtes aux légumes du jour', ingredients: $this->referenceIngredients(), steps: json_encode(self::referenceSteps(), JSON_THROW_ON_ERROR));

        self::assertTrue($data['success'] ?? false, json_encode($data, JSON_THROW_ON_ERROR));
        $id = $data['recipe']['id'];
        self::assertSame([
            ['position' => 1, 'text' => "Mettre l'eau à bouillir", 'durationMinutes' => 10, 'background' => true, 'ingredients' => [], 'utensils' => ['Casserole'], 'waitsFor' => []],
            ['position' => 2, 'text' => 'Couper les légumes', 'durationMinutes' => null, 'background' => false, 'ingredients' => ['Courgette', 'Poivron'], 'utensils' => [], 'waitsFor' => []],
            ['position' => 3, 'text' => 'Cuire les pâtes', 'durationMinutes' => 12, 'background' => true, 'ingredients' => ['Pâtes'], 'utensils' => ['Casserole'], 'waitsFor' => [1]],
            ['position' => 4, 'text' => 'Faire revenir les légumes', 'durationMinutes' => 8, 'background' => false, 'ingredients' => ['Courgette', 'Poivron'], 'utensils' => ['Poêle'], 'waitsFor' => []],
            ['position' => 5, 'text' => 'Égoutter, mélanger', 'durationMinutes' => null, 'background' => false, 'ingredients' => [], 'utensils' => [], 'waitsFor' => [3, 4]],
        ], $this->stepsOf($id));
        self::assertSame([[], [], [1], [], [3, 4]], array_column($data['recipe']['steps'], 'waitsFor'));
        $this->assertMercureUpdatePublished('/api/recipes/'.$id);
        $this->assertElasticsearchIndexDispatchedFor(Recipe::class, $id);
    }

    public function testAStepWaitingForALaterStepIsRefused(): void
    {
        $this->load();
        $steps = self::referenceSteps();
        $steps[2]['waitsFor'] = [5];

        $data = $this->call(UpdateRecipeTool::class, $this->id('pasta'), steps: json_encode($steps, JSON_THROW_ON_ERROR));

        self::assertArrayHasKey('error', $data);
        self::assertSame([], $this->stepsOf($this->id('pasta')));
    }

    public function testAStepUsingAnIngredientTheRecipeDoesNotListIsRefusedNamingTheStep(): void
    {
        $this->load();
        $steps = self::referenceSteps();
        $steps[1]['ingredientIds'] = ['Farine'];

        $data = $this->call(UpdateRecipeTool::class, $this->id('pasta'), steps: json_encode($steps, JSON_THROW_ON_ERROR));

        self::assertStringContainsString('Couper les légumes', $data['error'] ?? '');
        self::assertSame([], $this->stepsOf($this->id('pasta')));
    }

    public function testStepsThatAreNotJsonAreRefused(): void
    {
        $this->load();

        $data = $this->call(UpdateRecipeTool::class, $this->id('pasta'), steps: 'Couper, cuire');

        self::assertArrayHasKey('error', $data);
        self::assertSame([], $this->stepsOf($this->id('pasta')));
    }

    public function testUpdateRecipeReplacesTheStepsPublishesAndIndexes(): void
    {
        $this->load();
        $id = $this->id('pasta');

        $data = $this->call(UpdateRecipeTool::class, $id, steps: json_encode(self::referenceSteps(), JSON_THROW_ON_ERROR));

        self::assertTrue($data['success'] ?? false, json_encode($data, JSON_THROW_ON_ERROR));
        self::assertCount(5, $this->stepsOf($id));
        $payloads = $this->mercurePayloadsOn('/api/recipes/'.$id);
        self::assertNotEmpty($payloads);
        self::assertSame([1, 2, 3, 4, 5], array_column(end($payloads)['steps'], 'position'));
        $this->assertElasticsearchIndexDispatchedFor(Recipe::class, $id);
    }

    public function testChangingTheIngredientQuantitiesKeepsTheStepLinks(): void
    {
        $this->load();
        $id = $this->id('cooked');
        $before = $this->stepsOf($id);

        $data = $this->call(UpdateRecipeTool::class, $id, ingredients: json_encode([
            ['ingredientId' => $this->id('pasta_food'), 'quantity' => 500, 'unit' => 'g'],
        ], JSON_THROW_ON_ERROR));

        self::assertTrue($data['success'] ?? false, json_encode($data, JSON_THROW_ON_ERROR));
        self::assertSame($before, $this->stepsOf($id));
    }

    public function testAnIngredientAStepUsesCannotLeaveTheRecipe(): void
    {
        $this->load();
        $id = $this->id('cooked');
        $before = $this->stepsOf($id);

        $data = $this->call(UpdateRecipeTool::class, $id, ingredients: '[]');

        self::assertStringContainsString('Cuire les pâtes', $data['error'] ?? '');
        self::assertSame($before, $this->stepsOf($id));
    }

    public function testGetRecipeAnswersItsStepsInOrder(): void
    {
        $this->load();

        $steps = $this->call(GetRecipeTool::class, $this->id('cooked'))['recipe']['steps'];

        self::assertSame([
            [
                'id' => $this->id('cooked_boil'),
                'position' => 1,
                'text' => "Mettre l'eau à bouillir",
                'durationMinutes' => 10,
                'background' => true,
                'ingredients' => [],
                'utensils' => [['id' => $this->id('pot'), 'name' => 'Casserole']],
                'waitsFor' => [],
            ],
            [
                'id' => $this->id('cooked_cook'),
                'position' => 2,
                'text' => 'Cuire les pâtes',
                'durationMinutes' => 12,
                'background' => true,
                'ingredients' => [['id' => $this->id('pasta_food'), 'name' => 'Pâtes']],
                'utensils' => [['id' => $this->id('pot'), 'name' => 'Casserole']],
                'waitsFor' => [1],
            ],
        ], $steps);
    }
}
