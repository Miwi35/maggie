<?php

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Mcp\Tool\CreateMealTool;
use Maggie\Cookbook\Mcp\Tool\DeleteMealTool;
use Maggie\Cookbook\Mcp\Tool\GetMealsTool;
use Maggie\Core\Entity\User;
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

    public function testCreateMealPersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('meal.yaml');

        $recipe = $this->getFixture('pasta');

        $tool = self::getContainer()->get(CreateMealTool::class);
        $result = $tool('2026-03-20', 'lunch', (string) $recipe->getId());

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

    public function testGetMealsReturnsPlannedMeals(): void
    {
        $this->loadFixtures('meal.yaml');

        $recipe = $this->getFixture('pasta');
        $createTool = self::getContainer()->get(CreateMealTool::class);
        $createTool('2026-03-20', 'dinner', (string) $recipe->getId());

        $tool = self::getContainer()->get(GetMealsTool::class);
        $result = $tool('2026-03-19', '2026-03-21');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(1, $data['meals']);
        self::assertSame('dinner', $data['meals'][0]['slot']);
        self::assertCount(1, $data['meals'][0]['recipes']);
    }

    public function testDeleteMealRemovesPublishesAndDeletes(): void
    {
        $this->loadFixtures('meal.yaml');
        $this->loginUser($this->getFixture('test_user'));

        $createTool = self::getContainer()->get(CreateMealTool::class);
        $createResult = $createTool('2026-03-20', 'lunch');
        $createData = json_decode($createResult, true, 512, JSON_THROW_ON_ERROR);

        $this->resetMercure();
        $this->resetAsyncTransport();

        $tool = self::getContainer()->get(DeleteMealTool::class);
        $result = $tool($createData['meal']['id']);

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
