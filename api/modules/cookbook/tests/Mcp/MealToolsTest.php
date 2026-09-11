<?php

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Mcp\Tool\ManageMealsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class MealToolsTest extends KernelTestCase
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

    private function tool(): ManageMealsTool
    {
        return self::getContainer()->get(ManageMealsTool::class);
    }

    public function testListWithoutUserIsRefused(): void
    {
        $this->loadFixtures('meal.yaml');

        $data = json_decode(($this->tool())('list', fromDate: '2026-03-19', toDate: '2026-03-21'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('No user bound', $data['error']);
    }

    public function testListRequiresADateRange(): void
    {
        $this->loadFixtures('meal.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->tool())('list'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testUnknownActionIsRejected(): void
    {
        $this->loadFixtures('meal.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->tool())('cook'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('Unknown action', $data['error']);
    }

    public function testUpdateMovesTheMealAndPublishes(): void
    {
        $this->loadFixtures('meal.yaml');
        $this->loginFixtureUser();

        $created = json_decode(($this->tool())('create', date: '2026-03-20', slot: 'lunch'), true, 512, JSON_THROW_ON_ERROR);
        $this->resetMercure();

        $data = json_decode(($this->tool())('update', mealId: $created['meal']['id'], date: '2026-03-21', slot: 'dinner'), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame('2026-03-21', $data['meal']['date']);
        self::assertSame('dinner', $data['meal']['slot']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(Meal::class, $created['meal']['id']);
        // Stored in UTC; the meal is planned in the Paris time zone.
        self::assertSame('2026-03-21', $stored->getStartAt()->setTimezone(new \DateTimeZone('Europe/Paris'))->format('Y-m-d'));

        $this->assertMercureUpdatePublished('/meals/');
    }

    public function testCreateMealPersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('meal.yaml');
        $this->loginFixtureUser();

        $recipe = $this->getFixture('pasta');

        $result = ($this->tool())('create', date: '2026-03-20', slot: 'lunch', recipeIds: (string) $recipe->getId());

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('lunch', $data['meal']['slot']);
        self::assertSame('2026-03-20', $data['meal']['date']);
        self::assertStringContainsString('Pâtes à la tomate', $data['meal']['summary']);

        // DB persistence
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $meals = $em->getRepository(Meal::class)->findAll();
        self::assertCount(1, $meals);

        // Mercure: published to both /meals/ and /events/ topics
        $this->assertMercureUpdatePublished('/meals/');
        $this->assertMercureUpdatePublished('/events/');
        $this->assertMercureUpdateCount(2);

        // Elasticsearch: IndexDocumentCommand dispatched
        $this->assertElasticsearchIndexDispatched(Meal::class);
    }

    public function testListReturnsPlannedMeals(): void
    {
        $this->loadFixtures('meal.yaml');
        $this->loginFixtureUser();

        $recipe = $this->getFixture('pasta');
        ($this->tool())('create', date: '2026-03-20', slot: 'dinner', recipeIds: (string) $recipe->getId());

        $result = ($this->tool())('list', fromDate: '2026-03-19', toDate: '2026-03-21');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(1, $data['meals']);
        self::assertSame('dinner', $data['meals'][0]['slot']);
        self::assertCount(1, $data['meals'][0]['recipes']);
    }

    public function testDeleteMealRemovesPublishesAndDeletes(): void
    {
        $this->loadFixtures('meal.yaml');
        $this->loginFixtureUser();

        $createData = json_decode(($this->tool())('create', date: '2026-03-20', slot: 'lunch'), true, 512, JSON_THROW_ON_ERROR);

        $this->resetMercure();
        $this->resetAsyncTransport();

        $result = ($this->tool())('delete', mealId: $createData['meal']['id']);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        // DB deletion
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Meal::class, $createData['meal']['id']));

        // Mercure: delete published to both /meals/ and /events/ topics
        $this->assertMercureUpdatePublished('/meals/');
        $this->assertMercureUpdatePublished('/events/');
        $this->assertMercureUpdateCount(2);

        // Elasticsearch: DeleteDocumentCommand dispatched
        $this->assertElasticsearchDeleteDispatched('meals');
    }
}
