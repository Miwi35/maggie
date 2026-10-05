<?php

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Mcp\Tool\ManageMealsTool;
use PHPUnit\Framework\Attributes\DataProvider;
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

    public function testUpdateWithEmptyRecipeIdsEmptiesTheRecipes(): void
    {
        $this->loadFixtures('meal.yaml');
        $this->loginFixtureUser();

        $recipe = $this->getFixture('pasta');
        $created = json_decode(($this->tool())('create', date: '2026-03-20', slot: 'lunch', recipeIds: (string) $recipe->getId()), true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(1, $created['meal']['recipes']);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = json_decode(($this->tool())('update', mealId: $created['meal']['id'], recipeIds: ''), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertCount(0, $data['meal']['recipes']);
        self::assertSame('lunch', $data['meal']['slot']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(Meal::class, $created['meal']['id']);
        self::assertCount(0, $stored->getRecipes());
        self::assertSame('2026-03-20', $stored->getStartAt()->setTimezone(new \DateTimeZone('Europe/Paris'))->format('Y-m-d'));

        $this->assertMercureUpdatePublished('/meals/');
        $this->assertElasticsearchIndexDispatched(Meal::class);
    }

    /**
     * A day nobody could have meant is refused, not rolled over (MAG-251).
     *
     * `createFromFormat('!Y-m-d', '2026-13-45')` does not fail — it returns
     * 2027-02-14 — so a strict read is what keeps a meal off a day the model
     * never named. The tool answers an error object rather than throwing out of
     * the MCP loop, which is what the `\DomainException` in `__invoke`'s catch
     * list is for.
     *
     * @return iterable<string, array{string}>
     */
    public static function notDays(): iterable
    {
        yield 'a month and a day that do not exist' => ['2026-13-45'];
        yield 'the 31st of a 30-day month' => ['2026-04-31'];
        yield 'a day with a time' => ['2026-03-20 19:30'];
        yield 'words' => ['demain'];
    }

    #[DataProvider('notDays')]
    public function testCreateRefusesADayThatIsNotOne(string $day): void
    {
        $this->loadFixtures('meal.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->tool())('create', date: $day, slot: 'lunch'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data, "\"{$day}\" was accepted as a day");
        self::assertStringContainsString('YYYY-MM-DD', $data['error']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(0, $em->getRepository(Meal::class)->findAll(), 'a meal was written anyway');
    }

    #[DataProvider('notDays')]
    public function testUpdateRefusesADayThatIsNotOne(string $day): void
    {
        $this->loadFixtures('meal.yaml');
        $this->loginFixtureUser();

        $created = json_decode(($this->tool())('create', date: '2026-03-20', slot: 'lunch'), true, 512, JSON_THROW_ON_ERROR);

        $data = json_decode(($this->tool())('update', mealId: $created['meal']['id'], date: $day), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data, "\"{$day}\" was accepted as a day");

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(Meal::class, $created['meal']['id']);
        self::assertSame('2026-03-20', $stored->getDate()->format('Y-m-d'), 'the meal moved on a refused day');
    }

    /**
     * The range of `list` is lenient where `create` is strict: it is a window to
     * read, and the model does send full timestamps (see
     * GenerateGroceryListHandler). Only the day is kept, read in Paris.
     */
    public function testListAcceptsATimestampAsAnEndOfItsRange(): void
    {
        $this->loadFixtures('meal.yaml');
        $this->loginFixtureUser();

        ($this->tool())('create', date: '2026-03-20', slot: 'dinner');

        $data = json_decode(
            ($this->tool())('list', fromDate: '2026-03-20T00:00:00+01:00', toDate: '2026-03-20T23:30:00+01:00'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertArrayNotHasKey('error', $data);
        self::assertCount(1, $data['meals']);
        self::assertSame('2026-03-20', $data['meals'][0]['date']);
    }

    public function testListStillRefusesSomethingThatIsNotADateAtAll(): void
    {
        $this->loadFixtures('meal.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->tool())('list', fromDate: 'la semaine prochaine', toDate: '2026-03-21'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testUpdateUnknownMealReturnsAnError(): void
    {
        $this->loadFixtures('meal.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->tool())('update', mealId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', recipeIds: ''), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }
}
