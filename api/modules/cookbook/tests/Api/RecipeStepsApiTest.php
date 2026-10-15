<?php

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Elastic\Elasticsearch\Client;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\RecipeStep;
use Maggie\Cookbook\Entity\Utensil;
use Maggie\Core\Tests\Elasticsearch\RecordingElasticsearchTrait;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The steps of a recipe, read and written with the recipe (MAG-490). */
class RecipeStepsApiTest extends WebTestCase
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

    private function load(): void
    {
        $this->loadFixtures('RecipeStepsTest.yaml');
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
    private function response(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function get(string $uri): array
    {
        $this->client->request('GET', $uri, [], [], array_merge(['HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()));
        self::assertResponseIsSuccessful();

        return $this->response();
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

    /**
     * The steps of a recipe as stored, in a shape a test can compare.
     *
     * @return list<array{position: int, text: string, durationMinutes: int|null, background: bool, ingredients: list<string>, utensils: list<string>, waitsFor: list<int>}>
     */
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
     * The reference example of the project (spec « étapes et ustensiles »).
     *
     * @return list<array<string, mixed>>
     */
    private function referenceSteps(): array
    {
        return [
            ['text' => "Mettre l'eau à bouillir", 'durationMinutes' => 10, 'background' => true, 'utensils' => ['Casserole']],
            ['text' => 'Couper les légumes', 'ingredientIds' => [strtolower($this->id('zucchini_food')), '/api/ingredients/'.$this->id('pepper_food')]],
            ['text' => 'Cuire les pâtes', 'durationMinutes' => 12, 'background' => true, 'ingredientIds' => [$this->id('pasta_food')], 'utensils' => ['Casserole'], 'waitsFor' => [1]],
            ['text' => 'Faire revenir les légumes', 'durationMinutes' => 8, 'ingredientIds' => [$this->id('zucchini_food'), $this->id('pepper_food')], 'utensils' => [$this->id('pan')]],
            ['text' => 'Égoutter, mélanger', 'waitsFor' => [3, 4]],
        ];
    }

    /** @return list<array<string, mixed>> the reference example, as stored */
    private static function referenceStored(): array
    {
        return [
            ['position' => 1, 'text' => "Mettre l'eau à bouillir", 'durationMinutes' => 10, 'background' => true, 'ingredients' => [], 'utensils' => ['Casserole'], 'waitsFor' => []],
            ['position' => 2, 'text' => 'Couper les légumes', 'durationMinutes' => null, 'background' => false, 'ingredients' => ['Courgette', 'Poivron'], 'utensils' => [], 'waitsFor' => []],
            ['position' => 3, 'text' => 'Cuire les pâtes', 'durationMinutes' => 12, 'background' => true, 'ingredients' => ['Pâtes'], 'utensils' => ['Casserole'], 'waitsFor' => [1]],
            ['position' => 4, 'text' => 'Faire revenir les légumes', 'durationMinutes' => 8, 'background' => false, 'ingredients' => ['Courgette', 'Poivron'], 'utensils' => ['Poêle'], 'waitsFor' => []],
            ['position' => 5, 'text' => 'Égoutter, mélanger', 'durationMinutes' => null, 'background' => false, 'ingredients' => [], 'utensils' => [], 'waitsFor' => [3, 4]],
        ];
    }

    /** @return list<array<string, mixed>> */
    private function referenceIngredients(): array
    {
        return [
            ['ingredient' => '/api/ingredients/'.$this->id('pasta_food'), 'quantity' => 200, 'unit' => 'g'],
            ['ingredient' => '/api/ingredients/'.$this->id('zucchini_food'), 'quantity' => 1, 'unit' => 'piece'],
            ['ingredient' => '/api/ingredients/'.$this->id('pepper_food'), 'quantity' => 1, 'unit' => 'piece'],
        ];
    }

    public function testTheReferenceRecipeCreatedThroughTheApiReadsBackIdentical(): void
    {
        $this->load();

        $this->send('POST', '/api/recipes', [
            'name' => 'Pâtes aux légumes du jour',
            'ingredients' => $this->referenceIngredients(),
            'steps' => $this->referenceSteps(),
        ]);

        self::assertResponseStatusCodeSame(201);
        $id = $this->response()['id'];
        self::assertSame(self::referenceStored(), $this->stepsOf($id));
        $recipe = $this->em()->find(Recipe::class, $id);
        self::assertSame(['Casserole', 'Poêle'], array_map(static fn (Utensil $u) => $u->getName(), $recipe?->getUtensils() ?? []), 'a step utensil is added to the recipe');
        $this->assertMercureUpdatePublished('/api/recipes/'.$id);
        $payloads = $this->mercurePayloadsOn('/api/recipes/'.$id);
        self::assertSame([1, 2, 3, 4, 5], array_column(end($payloads)['steps'], 'position'));
        $this->assertElasticsearchIndexDispatchedFor(Recipe::class, $id);
    }

    public function testTheRecipeReadServesItsStepsInOrder(): void
    {
        $this->load();
        $this->send('POST', '/api/recipes', ['name' => 'Pâtes aux légumes du jour', 'ingredients' => $this->referenceIngredients(), 'steps' => $this->referenceSteps()]);
        $id = $this->response()['id'];

        $steps = $this->get('/api/recipes/'.$id)['steps'];

        self::assertSame([1, 2, 3, 4, 5], array_column($steps, 'position'));
        self::assertSame([10, null, 12, 8, null], array_column($steps, 'durationMinutes'));
        self::assertSame([true, false, true, false, false], array_column($steps, 'background'));
        self::assertSame([[], [], [1], [], [3, 4]], array_column($steps, 'waitsFor'));
        self::assertSame([['id' => $this->id('pasta_food'), 'name' => 'Pâtes']], $steps[2]['ingredients']);
        self::assertSame([['id' => $this->id('pan'), 'name' => 'Poêle']], $steps[3]['utensils']);
        self::assertSame("Mettre l'eau à bouillir", $steps[0]['text']);
        self::assertNotEmpty($steps[0]['id']);
    }

    public function testCreatingARecipeWithStepsRequiresAuthentication(): void
    {
        $this->loadFixtures('RecipeStepsTest.yaml');

        $this->send('POST', '/api/recipes', ['name' => 'Pâtes', 'ingredients' => $this->referenceIngredients(), 'steps' => $this->referenceSteps()], authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertSame(2, $this->em()->getRepository(RecipeStep::class)->count([]));
    }

    public function testAStepWaitingForALaterStepIsRefused(): void
    {
        $this->load();
        $steps = $this->referenceSteps();
        $steps[2]['waitsFor'] = [5];

        $this->send('PATCH', '/api/recipes/'.$this->id('pasta'), ['steps' => $steps]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame([], $this->stepsOf($this->id('pasta')));
    }

    /** @return iterable<string, array{string, mixed}> what step 2 is given */
    public static function stepsThatDoNotHoldTogether(): iterable
    {
        yield 'a step waiting for itself' => ['waitsFor', [2]];
        yield 'a step waiting for nothing that exists' => ['waitsFor', [0]];
        yield 'background without a duration' => ['background', true];
        yield 'a duration of zero' => ['durationMinutes', 0];
        yield 'a negative duration' => ['durationMinutes', -5];
        yield 'a duration that is not whole minutes' => ['durationMinutes', 2.5];
        yield 'a duration over a week' => ['durationMinutes', 2 ** 31];
        yield 'an empty text' => ['text', '  '];
        yield 'ingredients that are not a list' => ['ingredientIds', 'Courgette'];
    }

    #[DataProvider('stepsThatDoNotHoldTogether')]
    public function testStepsThatDoNotHoldTogetherAreRefusedAndNothingIsWritten(string $field, mixed $value): void
    {
        $this->load();
        $steps = $this->referenceSteps();
        $steps[1][$field] = $value;

        $this->send('PATCH', '/api/recipes/'.$this->id('pasta'), ['steps' => $steps]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame([], $this->stepsOf($this->id('pasta')));
    }

    public function testStepsThatAreNotAListAreRefused(): void
    {
        $this->load();

        $this->send('PATCH', '/api/recipes/'.$this->id('pasta'), ['steps' => ['first' => ['text' => 'Couper']]]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame([], $this->stepsOf($this->id('pasta')));
    }

    public function testAStepCannotUseAnIngredientTheRecipeDoesNotList(): void
    {
        $this->load();
        $steps = $this->referenceSteps();
        $steps[1]['ingredientIds'] = [$this->id('other_food')];

        $this->send('PATCH', '/api/recipes/'.$this->id('pasta'), ['steps' => $steps]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('Couper les légumes', $this->response()['detail'] ?? '');
        self::assertSame([], $this->stepsOf($this->id('pasta')));
    }

    public function testAStepCannotUseAnotherUsersUtensil(): void
    {
        $this->load();
        $steps = $this->referenceSteps();
        $steps[3]['utensils'] = [$this->id('other_oven')];

        $this->send('PATCH', '/api/recipes/'.$this->id('pasta'), ['steps' => $steps]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame([], $this->stepsOf($this->id('pasta')));
    }

    public function testAnIngredientAStepUsesCannotLeaveTheRecipe(): void
    {
        $this->load();
        $before = $this->stepsOf($this->id('cooked'));

        $this->send('PATCH', '/api/recipes/'.$this->id('cooked'), ['ingredients' => []]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('Cuire les pâtes', $this->response()['detail'] ?? '');
        self::assertSame($before, $this->stepsOf($this->id('cooked')));
        self::assertCount(1, $this->em()->find(Recipe::class, $this->id('cooked'))?->getIngredients() ?? []);
    }

    public function testAUtensilAStepUsesCannotLeaveTheRecipe(): void
    {
        $this->load();

        $this->send('PATCH', '/api/recipes/'.$this->id('cooked'), ['utensils' => []]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString("Mettre l'eau à bouillir", $this->response()['detail'] ?? '');
        self::assertCount(1, $this->em()->find(Recipe::class, $this->id('cooked'))?->getUtensils() ?? []);
    }

    public function testChangingTheIngredientQuantitiesKeepsTheStepLinks(): void
    {
        $this->load();
        $before = $this->stepsOf($this->id('cooked'));

        $this->send('PATCH', '/api/recipes/'.$this->id('cooked'), ['ingredients' => [
            ['ingredient' => '/api/ingredients/'.$this->id('pasta_food'), 'quantity' => 500, 'unit' => 'g'],
        ]]);

        self::assertResponseIsSuccessful();
        self::assertSame($before, $this->stepsOf($this->id('cooked')));
        self::assertSame(['Pâtes'], $this->stepsOf($this->id('cooked'))[1]['ingredients']);
    }

    public function testPatchReplacesTheWholeListAndAStepKeepsItsIdAtItsPosition(): void
    {
        $this->load();
        $id = $this->id('cooked');
        $firstId = $this->id('cooked_boil');

        $this->send('PATCH', '/api/recipes/'.$id, ['steps' => [
            ['text' => "Porter l'eau à ébullition", 'durationMinutes' => 8, 'background' => true],
        ]]);

        self::assertResponseIsSuccessful();
        self::assertSame([
            ['position' => 1, 'text' => "Porter l'eau à ébullition", 'durationMinutes' => 8, 'background' => true, 'ingredients' => [], 'utensils' => [], 'waitsFor' => []],
        ], $this->stepsOf($id));
        self::assertSame($firstId, (string) $this->em()->find(Recipe::class, $id)?->getSteps()[0]->getId());
        self::assertSame(1, $this->em()->getRepository(RecipeStep::class)->count(['recipe' => $id]));
        $payloads = $this->mercurePayloadsOn('/api/recipes/'.$id);
        self::assertNotEmpty($payloads);
        self::assertSame(["Porter l'eau à ébullition"], array_column(end($payloads)['steps'], 'text'));
        $this->assertElasticsearchIndexDispatchedFor(Recipe::class, $id);
    }

    public function testPatchAcceptsTheStepsAsTheApiServesThem(): void
    {
        $this->load();
        $id = $this->id('cooked');
        $before = $this->stepsOf($id);
        $read = $this->get('/api/recipes/'.$id);

        $this->send('PATCH', '/api/recipes/'.$id, ['steps' => $read['steps']]);

        self::assertResponseIsSuccessful();
        self::assertSame($before, $this->stepsOf($id));
    }

    public function testPatchWithoutStepsKeepsThemAndAnEmptyListRemovesThem(): void
    {
        $this->load();
        $id = $this->id('cooked');
        $before = $this->stepsOf($id);

        $this->send('PATCH', '/api/recipes/'.$id, ['name' => 'Pâtes au beurre salé']);
        self::assertResponseIsSuccessful();
        self::assertSame($before, $this->stepsOf($id));

        $this->send('PATCH', '/api/recipes/'.$id, ['steps' => []]);
        self::assertResponseIsSuccessful();
        self::assertSame([], $this->stepsOf($id));
        self::assertSame(0, $this->em()->getRepository(RecipeStep::class)->count([]));
    }

    public function testTheRecipeCollectionListsTheStepsOfEachRecipe(): void
    {
        $this->load();

        $byName = array_column($this->get('/api/recipes')['member'], 'steps', 'name');

        self::assertSame(["Mettre l'eau à bouillir", 'Cuire les pâtes'], array_column($byName['Pâtes au beurre'], 'text'));
        self::assertSame([], $byName['Pâtes aux légumes']);
    }

    /**
     * The real read goes through Elasticsearch, whose hydrated recipe does not
     * hold the steps: they must still come back, read from the database.
     */
    public function testTheRecipeServedFromElasticsearchStillServesItsSteps(): void
    {
        $this->load();
        /** @var Recipe $cooked */
        $cooked = $this->getFixture('cooked');
        $document = json_decode(json_encode($cooked->toSearchDocument(), JSON_THROW_ON_ERROR), true, 512, JSON_THROW_ON_ERROR);
        $id = (string) $cooked->getId();
        $this->client->disableReboot();
        self::getContainer()->set(Client::class, $this->recordingClient(static fn (string $method, string $path): array => str_ends_with($path, '/_doc/'.$id)
            ? [200, ['_index' => 'recipes', '_id' => $id, 'found' => true, '_source' => $document]]
            : [404, ['found' => false]]));
        $this->em();

        $read = $this->get('/api/recipes/'.$id);

        self::assertNotEmpty($this->requests, 'the read went to Elasticsearch');
        self::assertSame([1, 2], array_column($read['steps'], 'position'));
        self::assertSame([[], [1]], array_column($read['steps'], 'waitsFor'));
        self::assertSame([['id' => $this->id('pasta_food'), 'name' => 'Pâtes']], $read['steps'][1]['ingredients']);
        self::assertSame([['id' => $this->id('pot'), 'name' => 'Casserole']], $read['steps'][1]['utensils']);
    }

    public function testDeletingAUtensilTakesItOutOfTheStepsToo(): void
    {
        $this->load();
        $id = $this->id('cooked');

        $this->client->request('DELETE', '/api/utensils/'.$this->id('pot'), [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(204);
        self::assertSame([[], []], array_column($this->stepsOf($id), 'utensils'));
        $this->assertMercureUpdatePublished('/api/recipes/'.$id);
    }

    public function testDeletingARecipeDeletesItsSteps(): void
    {
        $this->load();

        $this->client->request('DELETE', '/api/recipes/'.$this->id('cooked'), [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(204);
        self::assertSame(0, $this->em()->getRepository(RecipeStep::class)->count([]));
    }
}
