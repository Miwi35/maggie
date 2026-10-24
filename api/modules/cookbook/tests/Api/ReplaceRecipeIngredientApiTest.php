<?php

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\RecipeIngredient;
use Maggie\Core\Tests\Elasticsearch\RecordingElasticsearchTrait;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** Replacing the product of a recipe line, and the steps that follow (MAG-601). */
class ReplaceRecipeIngredientApiTest extends WebTestCase
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
        $this->loadFixtures('ReplaceRecipeIngredientTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
        // Read from the database, as a real request would: the fixtures' recipes do not hold the lines that point at them.
        $this->em();
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** @param array<string, mixed> $payload */
    private function replace(string $recipeRef, array $payload, bool $authenticated = true): void
    {
        $this->client->request('POST', '/api/recipes/'.$this->id($recipeRef).'/replace-ingredient', [], [], array_merge([
            'CONTENT_TYPE' => 'application/json',
            'HTTP_ACCEPT' => 'application/json',
        ], $authenticated ? $this->authHeaders() : []), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    /** @return array<string, mixed> */
    private function response(): array
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

    /** @return array<int, array{text: string, ingredients: list<string>, waitsFor: list<int>}> by position */
    private function stepsOfBourguignon(): array
    {
        $recipe = $this->em()->find(Recipe::class, $this->id('bourguignon'));
        self::assertNotNull($recipe);

        $steps = [];
        foreach ($recipe->getSteps() as $step) {
            $steps[$step->getPosition()] = ['text' => $step->getText(), 'ingredients' => array_column($step->getIngredients(), 'name'), 'waitsFor' => $step->getWaitsFor()];
        }

        return $steps;
    }

    public function testReplacingAnIngredientRequiresAuthentication(): void
    {
        $this->loadFixtures('ReplaceRecipeIngredientTest.yaml');

        $this->replace('bourguignon', ['ingredientId' => $this->id('bacon_food'), 'with' => ['ingredientId' => $this->id('belly_food')]], false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testAnotherUsersRecipeIsNotFound(): void
    {
        $this->load();

        $this->replace('other_recipe', ['ingredientId' => $this->id('bacon_food'), 'with' => ['ingredientId' => $this->id('belly_food')]]);

        self::assertResponseStatusCodeSame(404);
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function badBodies(): iterable
    {
        yield 'no ingredient' => [['with' => ['ingredientId' => 'x']]];
        yield 'no replacement' => [['ingredientId' => 'x']];
        yield 'both replacements' => [['ingredientId' => 'x', 'with' => ['ingredientId' => 'y', 'ciqualAlimCode' => '1']]];
        yield 'quantity not positive' => [['ingredientId' => 'x', 'with' => ['ingredientId' => 'y'], 'quantity' => 0]];
        yield 'rewriteText not a boolean' => [['ingredientId' => 'x', 'with' => ['ingredientId' => 'y'], 'rewriteText' => 'yes']];
    }

    /** @param array<string, mixed> $body */
    #[DataProvider('badBodies')]
    public function testABadBodyIsRefused(array $body): void
    {
        $this->load();

        $this->replace('bourguignon', $body);

        self::assertResponseStatusCodeSame(400);
    }

    /** @return iterable<string, array{string, string}> the product to replace, the product it gets, as fixture references */
    public static function refusedReplacements(): iterable
    {
        yield 'a product the recipe does not have' => ['belly_food', 'bacon_food'];
        yield 'a product of another user' => ['bacon_food', 'other_food'];
        yield 'a product already on another line' => ['bacon_food', 'beef_food'];
    }

    #[DataProvider('refusedReplacements')]
    public function testARefusedReplacementWritesNothing(string $old, string $new): void
    {
        $this->load();
        $before = $this->stepsOfBourguignon();

        $this->replace('bourguignon', ['ingredientId' => $this->id($old), 'with' => ['ingredientId' => $this->id($new)]]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame($before, $this->stepsOfBourguignon());
        $this->assertNothingPublishedOn('/api/recipes/'.$this->id('bourguignon'));
    }

    public function testAnUnknownUnitOrIngredientIsRefused(): void
    {
        $this->load();

        $this->replace('bourguignon', ['ingredientId' => $this->id('bacon_food'), 'with' => ['ingredientId' => $this->id('belly_food')], 'unit' => 'handful']);
        self::assertResponseStatusCodeSame(400);

        $this->replace('bourguignon', ['ingredientId' => $this->id('bacon_food'), 'with' => ['ingredientId' => '01ARZ3NDEKTSV4RRFFQ69G5FAV']]);
        self::assertResponseStatusCodeSame(400);
    }

    public function testTheStepsFollowTheNewProductAndTheirTextIsLeftAlone(): void
    {
        $this->load();
        $before = $this->stepsOfBourguignon();

        $this->replace('bourguignon', ['ingredientId' => $this->id('bacon_food'), 'with' => ['ingredientId' => $this->id('belly_food')]]);

        self::assertResponseIsSuccessful();
        $answer = $this->response();
        self::assertFalse($answer['textRewritten']);
        self::assertSame(
            [
                [2, 'Faire revenir 200 g de lardons fumés dans la cocotte', 'Faire revenir 200 g de poitrine fumée dans la cocotte'],
                [6, 'Parsemer de lardons fumés et servir', 'Parsemer de poitrine fumée et servir'],
            ],
            array_map(static fn (array $s) => [$s['position'], $s['before'], $s['after']], $answer['textSuggestions']),
        );

        $after = $this->stepsOfBourguignon();
        self::assertSame(['Poitrine fumée'], $after[2]['ingredients']);
        self::assertSame(['Poitrine fumée'], $after[6]['ingredients']);
        self::assertSame($before[1], $after[1], 'the other steps are untouched');
        self::assertSame($before[4], $after[4]);
        self::assertSame($before[6]['waitsFor'], $after[6]['waitsFor']);
        foreach ([2, 6] as $position) {
            self::assertSame($before[$position]['text'], $after[$position]['text'], 'no text is rewritten without a choice');
        }

        $line = $this->lineOf('Poitrine fumée');
        self::assertSame(200.0, $line->getQuantity());
        self::assertSame('g', $line->getUnit()->value);
        self::assertSame(['Poitrine fumée'], array_column($answer['recipe']['steps'][1]['ingredients'], 'name'));
        $this->assertMercureUpdatePublished('/api/recipes/'.$this->id('bourguignon'));
        $this->assertElasticsearchIndexDispatchedFor(Recipe::class, $this->id('bourguignon'));
    }

    public function testRewriteTextChangesTheNameAndOnlyThenTheTexts(): void
    {
        $this->load();

        $this->replace('bourguignon', ['ingredientId' => $this->id('bacon_food'), 'with' => ['ingredientId' => $this->id('belly_food')], 'rewriteText' => true]);

        self::assertResponseIsSuccessful();
        self::assertTrue($this->response()['textRewritten']);
        $after = $this->stepsOfBourguignon();
        self::assertSame('Faire revenir 200 g de poitrine fumée dans la cocotte', $after[2]['text']);
        self::assertSame('Parsemer de poitrine fumée et servir', $after[6]['text']);
        self::assertSame('Couper le bœuf en cubes', $after[1]['text']);
        self::assertSame(['Poitrine fumée'], $after[2]['ingredients']);
    }

    public function testANewQuantityIsOfferedInTheTextToo(): void
    {
        $this->load();

        $this->replace('bourguignon', ['ingredientId' => $this->id('bacon_food'), 'with' => ['ingredientId' => $this->id('belly_food')], 'quantity' => 150]);

        self::assertResponseIsSuccessful();
        self::assertSame('Faire revenir 150 g de poitrine fumée dans la cocotte', $this->response()['textSuggestions'][0]['after']);
        self::assertSame(150.0, $this->lineOf('Poitrine fumée')->getQuantity());
        self::assertSame('Faire revenir 200 g de lardons fumés dans la cocotte', $this->stepsOfBourguignon()[2]['text']);
    }

    public function testTheSameProductWithANewQuantityOnlyOffersTheQuantity(): void
    {
        $this->load();

        $this->replace('bourguignon', ['ingredientId' => $this->id('bacon_food'), 'with' => ['ingredientId' => $this->id('bacon_food')], 'quantity' => 300, 'unit' => 'g']);

        self::assertResponseIsSuccessful();
        self::assertSame(
            [[2, 'Faire revenir 300 g de lardons fumés dans la cocotte']],
            array_map(static fn (array $s) => [$s['position'], $s['after']], $this->response()['textSuggestions']),
        );
        self::assertSame(['Lardons fumés'], $this->stepsOfBourguignon()[2]['ingredients']);
    }

    public function testTheIngredientsOfThePartialUpdateStillWork(): void
    {
        $this->load();

        $this->client->request('PATCH', '/api/recipes/'.$this->id('bourguignon'), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['name' => 'Bœuf bourguignon du dimanche'], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        self::assertSame(['Lardons fumés'], $this->stepsOfBourguignon()[2]['ingredients']);
    }

    private function lineOf(string $name): RecipeIngredient
    {
        $recipe = $this->em()->find(Recipe::class, $this->id('bourguignon'));
        self::assertNotNull($recipe);
        foreach ($recipe->getIngredients() as $line) {
            if ($line->getIngredientName() === $name) {
                return $line;
            }
        }

        self::fail("No line for $name.");
    }
}
