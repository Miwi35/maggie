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
        $this->client->request('PATCH', '/api/recipes/' . $recipe->getId(), [], [], array_merge([
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

        $this->client->request('PATCH', '/api/recipes/' . $pasta->getId(), [], [], [
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
}
