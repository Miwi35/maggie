<?php

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Grocery\Enum\Unit;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class IngredientApiTest extends WebTestCase
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
    private function patchIngredient(Ingredient $ingredient, array $payload): void
    {
        $this->client->request('PATCH', '/api/ingredients/'.$ingredient->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    private function reload(Ingredient $ingredient): Ingredient
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Ingredient::class)->find($ingredient->getId());
    }

    private function loadTomato(): Ingredient
    {
        $this->loadFixtures('IngredientApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        return $this->getFixture('tomato');
    }

    public function testPatchIngredientRequiresAuthentication(): void
    {
        $tomato = $this->loadTomato();

        $this->client->request('PATCH', '/api/ingredients/'.$tomato->getId(), [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
        ], json_encode(['kcalPer100g' => null], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchWithNullNutritionAndCiqualCodeClearsThem(): void
    {
        $tomato = $this->loadTomato();

        $this->patchIngredient($tomato, ['ciqualAlimCode' => null, 'kcalPer100g' => null, 'fatPer100g' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($tomato);
        self::assertNull($reloaded->getCiqualAlimCode());
        self::assertNull($reloaded->getKcalPer100g());
        self::assertNull($reloaded->getFatPer100g());
        self::assertSame(0.9, $reloaded->getProteinPer100g(), 'Fields left out of the payload are untouched');
        self::assertSame(2.8, $reloaded->getCarbsPer100g());
        self::assertSame(Unit::Gram, $reloaded->getDefaultUnit());
        $this->assertMercureUpdatePublished('/ingredients/');
        $this->assertElasticsearchIndexDispatched(Ingredient::class);
    }

    public function testPatchWithNullDefaultUnitClearsIt(): void
    {
        $tomato = $this->loadTomato();

        $this->patchIngredient($tomato, ['defaultUnit' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($tomato);
        self::assertNull($reloaded->getDefaultUnit());
        self::assertSame('Tomate', $reloaded->getName());
        self::assertSame(18.0, $reloaded->getKcalPer100g());
    }
}
