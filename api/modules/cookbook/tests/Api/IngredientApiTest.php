<?php

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Elastic\Elasticsearch\Client;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Core\Tests\Elasticsearch\RecordingElasticsearchTrait;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Enum\Unit;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class IngredientApiTest extends WebTestCase
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

    public function testPatchSendingBackTheWholeRecordReadBeforeSavesIt(): void
    {
        $tomato = $this->loadTomato();

        $this->client->request('GET', '/api/ingredients/'.$tomato->getId(), [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));
        self::assertResponseIsSuccessful();
        $record = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        $record['id'] = $record['@id'];
        $record['originId'] = (string) $tomato->getId();
        $record['name'] = 'Tomate cerise';

        $this->patchIngredient($tomato, $record);

        self::assertResponseIsSuccessful();
        self::assertSame('Tomate cerise', $this->reload($tomato)->getName());
        $this->assertMercureUpdatePublished('/ingredients/');
        $this->assertElasticsearchIndexDispatched(Ingredient::class);
    }

    public function testPatchSetsThePreferredAndTheFallbackStore(): void
    {
        $this->loadTomato();
        $onion = $this->getFixture('onion');
        $halles = $this->getFixture('halles');
        $bio = $this->getFixture('bio');

        $this->patchIngredient($onion, [
            'preferredStore' => '/api/stores/'.$halles->getId(),
            'fallbackStore' => '/api/stores/'.$bio->getId(),
        ]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($onion);
        self::assertSame((string) $halles->getId(), (string) $reloaded->getPreferredStore()?->getId());
        self::assertSame((string) $bio->getId(), (string) $reloaded->getFallbackStore()?->getId());
        self::assertSame(Unit::Pack, $reloaded->getPackagingUnit());
        $this->assertMercureUpdatePublished('/ingredients/');
        $this->assertElasticsearchIndexDispatched(Ingredient::class);
    }

    public function testPatchSendingBackTheStoresWhenTheIndexServesThemKeepsTheManagedStores(): void
    {
        $this->loadTomato();
        $onion = $this->getFixture('onion');
        $halles = $this->getFixture('halles');
        $bio = $this->getFixture('bio');
        /** @var EntityManagerInterface $em */
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $onion->setPreferredStore($halles)->setFallbackStore($bio);
        $em->flush();
        $this->serveStoresFromTheIndex($halles, $bio);

        $this->client->request('GET', '/api/ingredients/'.$onion->getId(), [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));
        $record = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        $record['id'] = $record['@id'];
        $record['originId'] = (string) $onion->getId();
        $record['packagingSize'] = 500;

        $this->patchIngredient($onion, $record);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($onion);
        self::assertSame(500.0, $reloaded->getPackagingSize());
        self::assertSame((string) $halles->getId(), (string) $reloaded->getPreferredStore()?->getId());
        self::assertSame((string) $bio->getId(), (string) $reloaded->getFallbackStore()?->getId());
        $this->assertMercureUpdatePublished('/ingredients/');
        $this->assertElasticsearchIndexDispatched(Ingredient::class);
    }

    public function testPatchWithNullStoresClearsThem(): void
    {
        $this->loadTomato();
        $onion = $this->getFixture('onion');
        $this->patchIngredient($onion, [
            'preferredStore' => '/api/stores/'.$this->getFixture('halles')->getId(),
            'fallbackStore' => '/api/stores/'.$this->getFixture('bio')->getId(),
        ]);

        $this->patchIngredient($onion, ['preferredStore' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($onion);
        self::assertNull($reloaded->getPreferredStore());
        self::assertSame((string) $this->getFixture('bio')->getId(), (string) $reloaded->getFallbackStore()?->getId());
    }

    public function testPatchWithoutStoresKeepsThem(): void
    {
        $this->loadTomato();
        $onion = $this->getFixture('onion');
        $this->patchIngredient($onion, ['preferredStore' => '/api/stores/'.$this->getFixture('halles')->getId()]);

        $this->patchIngredient($onion, ['name' => 'Oignon jaune']);

        self::assertResponseIsSuccessful();
        self::assertSame((string) $this->getFixture('halles')->getId(), (string) $this->reload($onion)->getPreferredStore()?->getId());
    }

    public function testPatchRefusesAStoreOfAnotherUser(): void
    {
        $this->loadTomato();
        $onion = $this->getFixture('onion');

        $this->patchIngredient($onion, ['preferredStore' => '/api/stores/'.$this->getFixture('other_user_store')->getId()]);

        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->reload($onion)->getPreferredStore());
    }

    public function testPatchRefusesAStoreSentAsAnEmbeddedObjectAndCreatesNone(): void
    {
        $this->loadTomato();
        $onion = $this->getFixture('onion');
        $stores = $this->storeCount();

        $this->patchIngredient($onion, ['preferredStore' => ['id' => (string) $this->getFixture('halles')->getId(), 'name' => 'Halles du voisin']]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame($stores, $this->storeCount());
        self::assertNull($this->reload($onion)->getPreferredStore());
    }

    private function storeCount(): int
    {
        /** @var EntityManagerInterface $em */
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        return $em->getRepository(Store::class)->count([]);
    }

    private function serveStoresFromTheIndex(Store ...$stores): void
    {
        $documents = [];
        foreach ($stores as $store) {
            $documents[(string) $store->getId()] = json_decode(json_encode($store->toSearchDocument(), JSON_THROW_ON_ERROR), true, 512, JSON_THROW_ON_ERROR);
        }
        $this->client->disableReboot();
        self::getContainer()->set(Client::class, $this->recordingClient(static function (string $method, string $path) use ($documents): array {
            foreach ($documents as $id => $document) {
                if (str_ends_with($path, '/_doc/'.$id)) {
                    return [200, ['_index' => 'stores', '_id' => $id, 'found' => true, '_source' => $document]];
                }
            }

            return [404, ['found' => false]];
        }));
        self::getContainer()->get('doctrine.orm.entity_manager');
    }

    public function testPatchThroughTheProductRouteSendingBackTheWholeRecordSavesIt(): void
    {
        $tomato = $this->loadTomato();

        $this->client->request('GET', '/api/products/'.$tomato->getId(), [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));
        self::assertResponseIsSuccessful();
        $record = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        $record['id'] = $record['@id'];
        $record['originId'] = (string) $tomato->getId();
        $record['name'] = 'Tomate cerise';

        $this->client->request('PATCH', '/api/products/'.$tomato->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($record, JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        self::assertSame('Tomate cerise', $this->reload($tomato)->getName());
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

    public function testPatchSetsThenClearsThePieceWeight(): void
    {
        $tomato = $this->loadTomato();

        $this->patchIngredient($tomato, ['pieceWeightGrams' => 120]);

        self::assertResponseIsSuccessful();
        self::assertSame(120.0, $this->reload($tomato)->getPieceWeightGrams());
        self::assertSame(18.0, $this->reload($tomato)->getKcalPer100g(), 'Fields left out of the payload are untouched');

        $this->patchIngredient($tomato, ['pieceWeightGrams' => null]);

        self::assertResponseIsSuccessful();
        self::assertNull($this->reload($tomato)->getPieceWeightGrams());
        $this->assertMercureUpdatePublished('/ingredients/');
        $this->assertElasticsearchIndexDispatched(Ingredient::class);
    }

    public function testPatchRefusesANonPositivePieceWeight(): void
    {
        $tomato = $this->loadTomato();
        $before = $this->reload($tomato)->getPieceWeightGrams();

        $this->patchIngredient($tomato, ['pieceWeightGrams' => -5]);

        self::assertResponseStatusCodeSame(422);
        self::assertSame($before, $this->reload($tomato)->getPieceWeightGrams());
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

    public function testPatchSetsThePackaging(): void
    {
        $tomato = $this->loadTomato();

        $this->patchIngredient($tomato, ['packagingUnit' => 'can', 'packagingSize' => 400, 'packagingSizeUnit' => 'g']);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($tomato);
        self::assertSame(Unit::Can, $reloaded->getPackagingUnit());
        self::assertSame(400.0, $reloaded->getPackagingSize());
        self::assertSame(Unit::Gram, $reloaded->getPackagingSizeUnit());
        self::assertSame(18.0, $reloaded->getKcalPer100g(), 'Fields left out of the payload are untouched');
        $this->assertMercureUpdatePublished('/ingredients/');
        $this->assertElasticsearchIndexDispatched(Ingredient::class);
    }

    public function testPatchWithNullPackagingClearsIt(): void
    {
        $tomato = $this->loadTomato();
        $this->patchIngredient($tomato, ['packagingUnit' => 'can', 'packagingSize' => 400, 'packagingSizeUnit' => 'g']);
        self::assertResponseIsSuccessful();

        $this->patchIngredient($tomato, ['packagingUnit' => null, 'packagingSize' => null, 'packagingSizeUnit' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($tomato);
        self::assertNull($reloaded->getPackagingUnit());
        self::assertNull($reloaded->getPackagingSize());
        self::assertNull($reloaded->getPackagingSizeUnit());
    }

    public function testPatchRefusesToClearOnlyTheSizeUnit(): void
    {
        $tomato = $this->loadTomato();
        $this->patchIngredient($tomato, ['packagingUnit' => 'can', 'packagingSize' => 400, 'packagingSizeUnit' => 'g']);
        self::assertResponseIsSuccessful();

        $this->patchIngredient($tomato, ['packagingSizeUnit' => null]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(Unit::Gram, $this->reload($tomato)->getPackagingSizeUnit());
    }

    public function testPostRefusesASizeWithoutItsUnit(): void
    {
        $this->loadTomato();

        $this->client->request('POST', '/api/ingredients', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'name' => 'Riz',
            'category' => 'grain',
            'packagingUnit' => 'pack',
            'packagingSize' => 500,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(400);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->getRepository(Ingredient::class)->findOneBy(['name' => 'Riz']));
    }

    public function testDeleteRequiresAuthentication(): void
    {
        $tomato = $this->loadTomato();

        $this->client->request('DELETE', '/api/ingredients/'.$tomato->getId());

        self::assertResponseStatusCodeSame(401);
    }

    public function testDeleteRemovesAnIngredientNoRecipeUses(): void
    {
        $tomato = $this->loadTomato();

        $this->client->request('DELETE', '/api/ingredients/'.$tomato->getId(), [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(204);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Ingredient::class, $tomato->getId()));
    }

    public function testDeleteRefusesAnIngredientARecipeStillUses(): void
    {
        $this->loadTomato();
        $basil = $this->getFixture('basil');

        $this->client->request('DELETE', '/api/ingredients/'.$basil->getId(), [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));

        self::assertResponseStatusCodeSame(409);
        self::assertStringContainsString('Pesto', (string) $this->client->getResponse()->getContent());
        self::assertNotNull($this->reload($basil), 'The ingredient is still there');
    }

    public function testDeleteThroughTheProductRouteRefusesAnIngredientARecipeStillUses(): void
    {
        $this->loadTomato();
        $basil = $this->getFixture('basil');

        $this->client->request('DELETE', '/api/products/'.$basil->getId(), [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));

        self::assertResponseStatusCodeSame(409);
        self::assertStringContainsString('Pesto', (string) $this->client->getResponse()->getContent());
        self::assertNotNull($this->reload($basil));
    }
}
