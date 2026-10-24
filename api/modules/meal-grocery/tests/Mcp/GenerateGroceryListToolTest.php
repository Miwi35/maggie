<?php

namespace Maggie\MealGrocery\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\MealGrocery\Mcp\Tool\GenerateGroceryListTool;
use Maggie\MealGrocery\Message\ChooseMealGroceriesCommand;
use Maggie\MealGrocery\Tests\Support\ChoosesMealGroceriesTrait;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

class GenerateGroceryListToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ChoosesMealGroceriesTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
    }

    public function testGenerateGroceryListReturnsSuccess(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(GenerateGroceryListTool::class);
        $result = $tool('2026-03-19', '2026-03-21');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertArrayHasKey('groceryList', $data);
        self::assertArrayHasKey('totalItems', $data['groceryList']);

        $this->assertMercureUpdatePublished('/grocery_lists/');
    }

    public function testItAddsTheRecurringItemsThatAreDueAndNoIngredientOfAMeal(): void
    {
        $this->loadMealFixtures();
        $this->planMeal('pasta', '+1 day');

        $data = $this->generate();

        self::assertSame(['Lait'], $this->labels($data));
    }

    public function testItNamesTheMealsWhoseIngredientsAreNotChosenYet(): void
    {
        $this->loadMealFixtures();
        $pasta = $this->planMeal('pasta', '+1 day');
        $gratin = $this->planMeal('gratin', '+2 days');
        $this->chooseEveryIngredientOf($gratin, 'gratin', (string) $this->getFixture('test_user')->getId());

        $data = $this->generate();

        self::assertSame([$pasta], array_column($data['mealsToChoose'], 'id'));
        self::assertSame('dinner', $data['mealsToChoose'][0]['slot']);
        self::assertSame($this->day('+1 day'), $data['mealsToChoose'][0]['date']);
    }

    public function testAMealWhoseChoiceIsMadeEvenForNothingIsNotNamed(): void
    {
        $this->loadMealFixtures();
        $mealId = $this->planMeal('pasta', '+1 day');
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new ChooseMealGroceriesCommand(mealId: $mealId, userId: (string) $this->getFixture('test_user')->getId(), chosen: []));

        self::assertSame([], $this->generate()['mealsToChoose']);
    }

    public function testAMealOutsideTheRangeIsNotNamed(): void
    {
        $this->loadMealFixtures();
        $this->planMeal('pasta', '+20 days');

        self::assertSame([], $this->generate()['mealsToChoose']);
    }

    public function testGeneratingTwiceInARowChangesNothing(): void
    {
        $this->loadMealFixtures();
        $this->planMeal('pasta', '+1 day');

        $first = $this->generate();
        $second = $this->generate();

        self::assertSame(['Lait'], $this->labels($first));
        self::assertSame($this->labels($first), $this->labels($second));
        self::assertSame($first['groceryList']['totalItems'], $second['groceryList']['totalItems']);
        self::assertSame($first['mealsToChoose'], $second['mealsToChoose']);
    }

    private function loadMealFixtures(): void
    {
        $this->loadFixtures(__DIR__.'/../MessageHandler/fixtures/MealGrocerySyncTest.yaml');
        $this->loginFixtureUser();
        $this->em()->clear();
    }

    /** @return array<string, mixed> */
    private function generate(): array
    {
        $this->em()->clear();

        return json_decode(
            (self::getContainer()->get(GenerateGroceryListTool::class))($this->day('today'), $this->day('+7 days')),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );
    }

    /**
     * @param array<string, mixed> $data
     *
     * @return list<string>
     */
    private function labels(array $data): array
    {
        $labels = [];
        foreach ($data['groceryList']['storeGroups'] as $group) {
            foreach ($group['items'] as $item) {
                $labels[] = $item['label'];
            }
        }
        sort($labels);

        return $labels;
    }

    private function planMeal(string $recipeRef, string $when): string
    {
        $envelope = self::getContainer()->get(MessageBusInterface::class)->dispatch(new CreateMealCommand(
            date: $this->day($when),
            slot: 'dinner',
            recipeIds: [(string) $this->getFixture($recipeRef)->getId()],
            userId: (string) $this->getFixture('test_user')->getId(),
        ));

        return (string) $envelope->last(HandledStamp::class)->getResult()->getId();
    }

    private function day(string $when): string
    {
        return (new \DateTimeImmutable($when, new \DateTimeZone('Europe/Paris')))->format('Y-m-d');
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
