<?php

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Cookbook\Entity\Recipe;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class RecipeApiTest extends WebTestCase
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

    /** @param array<string, mixed> $payload */
    private function patchRecipe(Recipe $recipe, array $payload): void
    {
        $this->client->request('PATCH', '/api/recipes/'.$recipe->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    private function reload(Recipe $recipe): Recipe
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Recipe::class)->find($recipe->getId());
    }

    private function loadPasta(): Recipe
    {
        $this->loadFixtures('RecipeApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        return $this->getFixture('pasta');
    }

    public function testPatchRecipeRequiresAuthentication(): void
    {
        $pasta = $this->loadPasta();

        $this->client->request('PATCH', '/api/recipes/'.$pasta->getId(), [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
        ], json_encode(['notes' => null], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchWithNullNotesClearsThem(): void
    {
        $pasta = $this->loadPasta();

        $this->patchRecipe($pasta, ['notes' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($pasta);
        self::assertNull($reloaded->getNotes());
        self::assertSame('Pâtes à la tomate', $reloaded->getName());
        self::assertSame(4, $reloaded->getServings());
        self::assertSame(['pasta', 'italian'], $reloaded->getTags());
        $this->assertMercureUpdatePublished('/recipes/');
        $this->assertElasticsearchIndexDispatched(Recipe::class);
    }

    public function testPatchWithoutNotesKeepsThem(): void
    {
        $pasta = $this->loadPasta();

        $this->patchRecipe($pasta, ['name' => 'Pâtes bolognaise']);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($pasta);
        self::assertSame('Pâtes bolognaise', $reloaded->getName());
        self::assertSame('Ajouter du basilic', $reloaded->getNotes());
    }

    /** @return array<string, mixed> */
    private function fetchRecipe(Recipe $recipe): array
    {
        $this->client->request('GET', '/api/recipes/'.$recipe->getId(), [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));
        self::assertResponseIsSuccessful();

        return json_decode((string) $this->client->getResponse()->getContent(), true, flags: JSON_THROW_ON_ERROR);
    }

    /** @return array<string, array{float, string}> quantity and unit by ingredient name */
    private function lines(Recipe $recipe): array
    {
        $lines = [];
        foreach ($this->reload($recipe)->getIngredients() as $line) {
            $lines[$line->getIngredientName()] = [$line->getQuantity(), $line->getUnit()->value];
        }
        ksort($lines);

        return $lines;
    }

    /** @return list<array<string, mixed>> */
    private function readLines(Recipe $recipe): array
    {
        $lines = $this->fetchRecipe($recipe)['ingredients'];
        usort($lines, static fn (array $a, array $b) => $a['ingredientName'] <=> $b['ingredientName']);

        return $lines;
    }

    public function testPatchIngredientsRequiresAuthentication(): void
    {
        $pasta = $this->loadPasta();

        $this->client->request('PATCH', '/api/recipes/'.$pasta->getId(), [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
        ], json_encode(['ingredients' => []], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testReadLinesExposeTheCiqualCodeOfTheirIngredient(): void
    {
        $pasta = $this->loadPasta();
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();

        $lines = $this->readLines($pasta);

        self::assertSame(['Pâtes', 'Tomate'], array_column($lines, 'ingredientName'));
        self::assertSame(['9810', '20047'], array_column($lines, 'ciqualAlimCode'));
    }

    public function testPatchSendingBackTheLinesAsReadChangesTheQuantity(): void
    {
        $pasta = $this->loadPasta();
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();

        $lines = $this->readLines($pasta);
        $lines[0]['quantity'] = 300;
        $this->patchRecipe($pasta, ['ingredients' => $lines]);

        self::assertResponseIsSuccessful();
        self::assertSame(['Pâtes' => [300.0, 'g'], 'Tomate' => [3.0, 'piece']], $this->lines($pasta));
        $this->assertMercureUpdatePublished('/recipes/');
        $this->assertElasticsearchIndexDispatched(Recipe::class);
        self::assertEquals(300, $this->readLines($pasta)[0]['quantity']);
    }

    public function testPatchSendingBackTheWholeRecordAsTheAdminDoesChangesTheQuantity(): void
    {
        $pasta = $this->loadPasta();
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();

        // react-admin's Hydra data provider replaces `id` by the IRI and adds `originId`.
        $record = $this->fetchRecipe($pasta);
        $record['id'] = $record['@id'];
        $record['originId'] = (string) $pasta->getId();
        usort($record['ingredients'], static fn (array $a, array $b) => $a['ingredientName'] <=> $b['ingredientName']);
        $record['ingredients'][0]['quantity'] = 300;
        $this->patchRecipe($pasta, $record);

        self::assertResponseIsSuccessful();
        self::assertSame(['Pâtes' => [300.0, 'g'], 'Tomate' => [3.0, 'piece']], $this->lines($pasta));
    }

    public function testPatchChangesTheUnitOfALine(): void
    {
        $pasta = $this->loadPasta();
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();

        $lines = $this->readLines($pasta);
        $lines[0]['quantity'] = 0.2;
        $lines[0]['unit'] = 'kg';
        $this->patchRecipe($pasta, ['ingredients' => $lines]);

        self::assertResponseIsSuccessful();
        self::assertSame(['Pâtes' => [0.2, 'kg'], 'Tomate' => [3.0, 'piece']], $this->lines($pasta));
    }

    public function testPatchReplacesTheIngredientOfALineByCiqualCode(): void
    {
        $pasta = $this->loadPasta();
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();

        $lines = $this->readLines($pasta);
        $lines[0]['ciqualAlimCode'] = '20047';
        $this->patchRecipe($pasta, ['ingredients' => $lines]);

        self::assertResponseIsSuccessful();
        $read = $this->readLines($pasta);
        self::assertSame(['Tomate', 'Tomate'], array_column($read, 'ingredientName'));
        $quantities = array_column($read, 'quantity');
        sort($quantities);
        self::assertEquals([3, 200], $quantities);
    }

    public function testPatchRemovesALine(): void
    {
        $pasta = $this->loadPasta();
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();

        $lines = $this->readLines($pasta);
        $this->patchRecipe($pasta, ['ingredients' => [$lines[1]]]);

        self::assertResponseIsSuccessful();
        self::assertSame(['Tomate' => [3.0, 'piece']], $this->lines($pasta));
        $this->assertElasticsearchIndexDispatched(Recipe::class);
    }

    /** @return iterable<string, array{mixed, mixed}> */
    public static function invalidLines(): iterable
    {
        yield 'negative quantity' => [-5, 'g'];
        yield 'zero quantity' => [0, 'g'];
        yield 'non numeric quantity' => ['beaucoup', 'g'];
        yield 'missing quantity' => [null, 'g'];
        yield 'unknown unit' => [100, 'poignee'];
    }

    #[\PHPUnit\Framework\Attributes\DataProvider('invalidLines')]
    public function testPatchRejectsAnInvalidLineAndKeepsTheRecipe(mixed $quantity, mixed $unit): void
    {
        $pasta = $this->loadPasta();
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();

        $lines = $this->readLines($pasta);
        $lines[0]['quantity'] = $quantity;
        $lines[0]['unit'] = $unit;
        $this->patchRecipe($pasta, ['ingredients' => $lines]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(['Pâtes' => [200.0, 'g'], 'Tomate' => [3.0, 'piece']], $this->lines($pasta));
    }
}
