<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\MealGroceryContribution;
use Maggie\Cookbook\Mcp\Tool\ManageMealGroceriesTool;
use Maggie\Cookbook\Mcp\Tool\ManageMealsTool;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** `manage_meal_groceries`, and the preview `manage_meals create` answers (MAG-295). */
class MealGroceryToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    private const FIXTURES = __DIR__.'/../Service/fixtures/MealGroceryChoiceTest.yaml';

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures(self::FIXTURES);
        $this->em()->clear();
    }

    public function testWithoutAUserNothingIsRead(): void
    {
        $data = $this->call('preview', mealId: (string) $this->getFixture('other_meal')->getId());

        self::assertStringContainsString('No user bound', $data['error']);
    }

    public function testUnknownActionIsRejected(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('cook');

        self::assertStringContainsString('Unknown action', $data['error']);
    }

    public function testAnotherUsersMealIsNotFound(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('preview', mealId: (string) $this->getFixture('other_meal')->getId());

        self::assertStringContainsString('Meal not found', $data['error']);
    }

    public function testCreateAnswersThePreviewSoMaggieCanAskWhatToBuy(): void
    {
        $this->loginFixtureUser();

        $created = $this->createCurry();

        $byName = array_column($created['groceryPreview']['ingredients'], null, 'name');
        self::assertTrue($byName['Riz']['suggested']);
        self::assertFalse($byName['Légumes pour couscous']['suggested']);
        self::assertSame($created['meal']['id'], $created['groceryPreview']['mealId']);
    }

    public function testPreviewListsTheIngredientsWithTheirStock(): void
    {
        $this->loginFixtureUser();
        $mealId = $this->createCurry()['meal']['id'];

        $data = $this->call('preview', mealId: $mealId);

        $byName = array_column($data['ingredients'], null, 'name');
        self::assertSame(['quantity' => 1, 'unit' => 'pack'], $byName['Riz']['toBuy']);
        self::assertSame('out', $byName['Riz']['stockState']);
        self::assertSame('low', $byName['Oignon']['stockState']);
        self::assertNull($data['groceryChoiceMadeAt']);
    }

    public function testAddPutsOnlyTheChosenIngredientsOnTheList(): void
    {
        $this->loginFixtureUser();
        $mealId = $this->createCurry()['meal']['id'];
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->call('add', mealId: $mealId, ingredientIds: (string) $this->getFixture('rice')->getId().', '.$this->getFixture('onion')->getId());

        self::assertTrue($data['success']);
        self::assertNotNull($data['groceryChoiceMadeAt']);
        self::assertSame(['Oignon 2 piece', 'Riz 1 pack'], $this->list());
        self::assertCount(2, $this->em()->getRepository(MealGroceryContribution::class)->findAll());
        self::assertNotNull($this->em()->find(Meal::class, $mealId)?->getGroceryChoiceMadeAt());
        $this->assertMercureUpdatePublished('/api/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testAddRefusesAnIngredientOfNoRecipeOfTheMeal(): void
    {
        $this->loginFixtureUser();
        $mealId = $this->createCurry()['meal']['id'];

        $data = $this->call('add', mealId: $mealId, ingredientIds: (string) $this->getFixture('fish')->getId());

        self::assertStringContainsString('Not an ingredient', $data['error']);
        self::assertNull($this->em()->find(Meal::class, $mealId)?->getGroceryChoiceMadeAt());
    }

    public function testAddNeedsTheIngredients(): void
    {
        $this->loginFixtureUser();
        $mealId = $this->createCurry()['meal']['id'];

        $data = $this->call('add', mealId: $mealId);

        self::assertStringContainsString('ingredientIds', $data['error']);
    }

    /** @return array<string, mixed> */
    private function createCurry(): array
    {
        $tool = self::getContainer()->get(ManageMealsTool::class);
        $created = json_decode($tool(
            'create',
            date: (new \DateTimeImmutable('+1 day', new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: 'dinner',
            recipeIds: (string) $this->getFixture('curry')->getId(),
        ), true, 512, \JSON_THROW_ON_ERROR);
        $this->em()->clear();

        return $created;
    }

    /** @return array<string, mixed> */
    private function call(string $action, ?string $mealId = null, ?string $ingredientIds = null): array
    {
        $tool = self::getContainer()->get(ManageMealGroceriesTool::class);
        $data = json_decode($tool($action, $mealId, $ingredientIds), true, 512, \JSON_THROW_ON_ERROR);
        $this->em()->clear();

        return $data;
    }

    /** @return list<string> */
    private function list(): array
    {
        $lines = [];
        foreach ($this->em()->getRepository(GroceryItem::class)->findAll() as $item) {
            $lines[] = $item->getLabel().' '.$item->getQuantity().' '.$item->getUnit()?->value;
        }
        sort($lines);

        return $lines;
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
