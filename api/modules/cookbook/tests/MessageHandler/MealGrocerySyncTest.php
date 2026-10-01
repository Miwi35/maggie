<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\MealGroceryContribution;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Cookbook\Message\DeleteMealCommand;
use Maggie\Cookbook\Message\GenerateGroceryListCommand;
use Maggie\Cookbook\Message\UpdateMealCommand;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Meals and the grocery list, kept in step (MAG-116).
 *
 * Every test here was a bug: changing a meal left the old ingredients to buy,
 * deleting one left all of them, generating twice bought everything twice,
 * every recurring item came back whatever its frequency, and the screens
 * watching the list never heard about any of it.
 */
class MealGrocerySyncTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    private const TOMORROW = '+1 day';

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('MealGrocerySyncTest.yaml');
        $this->loginFixtureUser();

        // Alice builds each recipe's ingredient collection from the other
        // side, so in memory a recipe looks ingredient-less. Detach
        // everything and the handlers read what the database really holds —
        // which is what they get in production.
        $this->em()->clear();
    }

    public function testPlanningAMealPutsItsIngredientsOnTheListAndPublishesIt(): void
    {
        $this->planMeal('pasta');

        self::assertSame(['Pâtes' => 400.0, 'Tomate' => 4.0], $this->list());

        $this->assertMercureUpdatePublished('/api/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testSwappingARecipeTakesTheOldIngredientsOffTheList(): void
    {
        $mealId = $this->planMeal('pasta');
        $this->resetMercure();

        $this->bus()->dispatch(new UpdateMealCommand(
            mealId: $mealId,
            date: null,
            slot: null,
            recipeIds: [(string) $this->getFixture('gratin')->getId()],
        ));

        // The pasta is gone, the tomatoes are down to what the gratin needs.
        self::assertSame(['Parmesan' => 80.0, 'Tomate' => 2.0], $this->list());
        $this->assertMercureUpdatePublished('/api/grocery_lists/');
    }

    public function testEmptyingAMealOfItsRecipesEmptiesItsShareOfTheList(): void
    {
        $mealId = $this->planMeal('pasta');

        $this->bus()->dispatch(new UpdateMealCommand(mealId: $mealId, date: null, slot: null, recipeIds: []));

        self::assertSame([], $this->list());
        self::assertSame(0, $this->contributionCount());
    }

    public function testCancellingAMealTakesItsIngredientsOffTheList(): void
    {
        $mealId = $this->planMeal('pasta');
        $this->resetMercure();

        $this->bus()->dispatch(new DeleteMealCommand(mealId: $mealId));

        self::assertSame([], $this->list());
        self::assertSame(0, $this->contributionCount());
        $this->assertMercureUpdatePublished('/api/grocery_lists/');
    }

    public function testTwoMealsShareOneLineAndEachOnlyTakesBackItsOwnShare(): void
    {
        $this->planMeal('pasta');
        $gratinId = $this->planMeal('gratin');

        // One tomato line, not two: 4 for the pasta plus 2 for the gratin.
        self::assertSame(['Parmesan' => 80.0, 'Pâtes' => 400.0, 'Tomate' => 6.0], $this->list());

        $this->bus()->dispatch(new DeleteMealCommand(mealId: $gratinId));

        self::assertSame(['Pâtes' => 400.0, 'Tomate' => 4.0], $this->list());
    }

    public function testALineAlreadyInTheBasketSurvivesTheMealItCameFrom(): void
    {
        $mealId = $this->planMeal('pasta');
        $this->check('Tomate');

        $this->bus()->dispatch(new DeleteMealCommand(mealId: $mealId));

        // Bought is bought: taking it off the list would not put it back on
        // the shelf, and the shopper would wonder what happened.
        self::assertSame(['Tomate' => 4.0], $this->list());
    }

    public function testGeneratingTwiceDoesNotBuyTheSameMealTwice(): void
    {
        $this->planMeal('pasta');

        $this->generate();
        $this->generate();

        self::assertSame(['Lait' => 1.0, 'Pâtes' => 400.0, 'Tomate' => 4.0], $this->list());
    }

    public function testGenerationOnlyAddsRecurringItemsWhoseFrequencyHasRunOut(): void
    {
        $this->generate();

        // The bleach is monthly and was added three days ago.
        self::assertSame(['Lait' => 1.0], $this->list());

        $today = new \DateTimeImmutable('today', new \DateTimeZone('Europe/Paris'));
        self::assertSame($today->format('Y-m-d'), $this->recurring('Lait')->getLastAddedAt()?->format('Y-m-d'));
        self::assertNotSame($today->format('Y-m-d'), $this->recurring('Javel')->getLastAddedAt()?->format('Y-m-d'));
        $this->assertElasticsearchIndexDispatched(RecurringGroceryItem::class);
    }

    public function testARecurringItemDueAgainIsAddedOnTheNextGeneration(): void
    {
        $this->generate();
        self::assertSame(['Lait' => 1.0], $this->list());

        // A week later, the milk is wanted again — and the bottle bought in
        // the meantime no longer stands in the way.
        $this->check('Lait');
        $this->recurring('Lait')->setLastAddedAt(new \DateTimeImmutable('-8 days'));
        $this->em()->flush();

        $this->generate();

        self::assertSame(['Lait' => 1.0, 'Lait ' => 1.0], $this->labelledList());
    }

    /** Plans tomorrow's dinner from one fixture recipe and returns its id. */
    private function planMeal(string $recipeRef): string
    {
        $envelope = $this->bus()->dispatch(new CreateMealCommand(
            date: (new \DateTimeImmutable(self::TOMORROW, new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: 'dinner',
            recipeIds: [(string) $this->getFixture($recipeRef)->getId()],
            userId: (string) $this->user()->getId(),
        ));

        return (string) $envelope->last(HandledStamp::class)->getResult()->getId();
    }

    private function generate(): void
    {
        $day = new \DateTimeImmutable(self::TOMORROW, new \DateTimeZone('Europe/Paris'));

        $this->bus()->dispatch(new GenerateGroceryListCommand(
            userId: (string) $this->user()->getId(),
            fromDate: $day->format('Y-m-d'),
            toDate: $day->format('Y-m-d'),
        ));
    }

    private function check(string $label): void
    {
        foreach ($this->groceryList()->getItems() as $item) {
            if ($item->getLabel() === $label) {
                $item->setChecked(true);
            }
        }

        $this->em()->flush();
    }

    /**
     * The list as stored, label => quantity, sorted so assertions read in one
     * line and do not depend on insertion order.
     *
     * @return array<string, float|null>
     */
    private function list(): array
    {
        $this->em()->clear();

        $items = [];
        foreach ($this->groceryList()->getItems() as $item) {
            $items[$item->getLabel()] = $item->getQuantity();
        }
        ksort($items);

        return $items;
    }

    /**
     * Same, but duplicate labels keep their own entry (padded with a space),
     * so a test can tell one line from two.
     *
     * @return array<string, float|null>
     */
    private function labelledList(): array
    {
        $this->em()->clear();

        $items = [];
        foreach ($this->groceryList()->getItems() as $item) {
            $label = $item->getLabel();
            while (isset($items[$label])) {
                $label .= ' ';
            }
            $items[$label] = $item->getQuantity();
        }
        ksort($items);

        return $items;
    }

    private function groceryList(): GroceryList
    {
        return $this->em()->getRepository(GroceryList::class)->findOneBy([])
            ?? throw new \LogicException('No grocery list was created.');
    }

    private function recurring(string $label): RecurringGroceryItem
    {
        return $this->em()->getRepository(RecurringGroceryItem::class)->findOneBy(['customLabel' => $label])
            ?? throw new \LogicException("No recurring item labelled {$label}.");
    }

    private function contributionCount(): int
    {
        return count($this->em()->getRepository(MealGroceryContribution::class)->findAll());
    }

    private function user(): User
    {
        return $this->em()->getRepository(User::class)->findOneBy([])
            ?? throw new \LogicException('No user.');
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function bus(): MessageBusInterface
    {
        return self::getContainer()->get(MessageBusInterface::class);
    }
}
