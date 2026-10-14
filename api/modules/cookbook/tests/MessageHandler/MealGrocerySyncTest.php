<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\MealGroceryContribution;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Message\ChooseMealGroceriesCommand;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Cookbook\Message\DeleteMealCommand;
use Maggie\Cookbook\Message\DeleteRecipeCommand;
use Maggie\Cookbook\Message\GenerateGroceryListCommand;
use Maggie\Cookbook\Message\UpdateMealCommand;
use Maggie\Cookbook\Message\UpdateRecipeCommand;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Message\AddRecurringGroceryItemCommand;
use Maggie\Grocery\Message\RemoveGroceryItemCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Meals and the grocery list (MAG-116, MAG-300).
 *
 * A meal puts nothing on the list by itself: only the owner's choice does.
 * What a meal that has chosen still does is follow its day — a perishable
 * planned three days later is bought three days later, at the same quantity —
 * and give its lines back when it goes. Generating the list brings the
 * recurring items along, and only those.
 */
class MealGrocerySyncTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    private const TOMORROW = '+1 day';
    private const GROCERY_TOPIC = '/api/grocery_lists/';

    protected function setUp(): void
    {
        self::bootKernel();
        $this->given();
    }

    /**
     * The world, optionally with more of it than the base fixture describes.
     *
     * Alice builds each recipe's ingredient collection from the other side, so
     * in memory a recipe looks ingredient-less. Detaching everything makes the
     * handlers read what the database really holds — which is what they get in
     * production.
     */
    private function given(string ...$extraFixtures): void
    {
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('MealGrocerySyncTest.yaml', ...$extraFixtures);
        $this->loginFixtureUser();
        $this->em()->clear();
    }

    public function testPlanningAMealPutsNothingOnTheList(): void
    {
        // Neither a meal that has not chosen (the change from MAG-295, where it
        // still derived its ingredients) nor the list screens hear about it.
        $this->planMeal('pasta');

        self::assertSame(0, $this->itemCount());
        self::assertSame(0, $this->contributionCount());
        self::assertSame(0, $this->groceryUpdateCount());
    }

    public function testPlanningAMealLeavesTheRestOfTheListAsItWas(): void
    {
        $gratinId = $this->planMeal('gratin');
        $this->chooseAll($gratinId, 'parmesan', 'tomato');
        $this->resetMercure();

        $this->planMeal('pasta');

        self::assertSame(['Parmesan' => 80.0, 'Tomate' => 2.0], $this->list());
        self::assertSame(0, $this->groceryUpdateCount(), 'nothing moved, nothing to publish');
    }

    public function testChoosingPutsTheChosenLinesOnTheListAndPublishesIt(): void
    {
        $mealId = $this->planMeal('pasta');
        $this->resetMercure();

        $this->chooseAll($mealId, 'pasta_product', 'tomato');

        self::assertSame(['Pâtes' => 400.0, 'Tomate' => 4.0], $this->list());
        self::assertSame(1, $this->groceryUpdateCount(), 'the grocery list must be published exactly once');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testSwappingARecipeLeavesTheListAlone(): void
    {
        $mealId = $this->planChosenPasta();
        $this->resetMercure();

        $this->replaceRecipes($mealId, 'gratin');

        self::assertSame(['Pâtes 1 pack'], $this->unitList());
        self::assertSame(0, $this->groceryUpdateCount(), 'nothing moved, nothing to publish');
    }

    public function testSwappingTheRecipeOfAMealThatHasNotChosenPutsNothingOnTheList(): void
    {
        $mealId = $this->planMeal('pasta');

        $this->replaceRecipes($mealId, 'gratin');

        self::assertSame(0, $this->itemCount());
    }

    public function testEmptyingAMealOfItsRecipesLeavesItsLinesOnTheList(): void
    {
        $mealId = $this->planMeal('pasta');
        $this->chooseAll($mealId, 'pasta_product', 'tomato');

        $this->bus()->dispatch(new UpdateMealCommand(mealId: $mealId, date: null, slot: null, recipeIds: []));

        // What the owner chose stays his to untick; the meal is not re-derived.
        self::assertSame(['Pâtes' => 400.0, 'Tomate' => 4.0], $this->list());
        self::assertSame(2, $this->contributionCount());
    }

    public function testEditingARecipeLeavesTheListAlone(): void
    {
        $this->planChosenPasta();
        $this->resetMercure();

        $this->editRecipe('pasta', [['pasta_product', 600, 'g'], ['tomato', 4, 'piece'], ['parmesan', 50, 'g']]);

        self::assertSame(['Pâtes 1 pack'], $this->unitList());
        self::assertSame(0, $this->groceryUpdateCount(), 'nothing moved, nothing to publish');
    }

    public function testEditingARecipeOfAMealThatHasNotChosenPutsNothingOnTheList(): void
    {
        $this->planMeal('pasta');

        $this->editRecipe('pasta', [['pasta_product', 600, 'g'], ['tomato', 4, 'piece']]);

        self::assertSame(0, $this->itemCount());
    }

    public function testMovingAMealThatHasChosenMovesTheBuyAfterOfItsLinesAndNotTheirQuantity(): void
    {
        $this->givePackaging('fish', 400);
        $mealId = $this->planMealOn('fish_dish', '+10 days');
        $this->chooseAll($mealId, 'fish');
        self::assertSame(['Cabillaud 1 pack'], $this->unitList());
        self::assertSame($this->day('+8 days'), $this->item('Cabillaud')->getBuyAfter()?->format('Y-m-d'));
        $this->resetMercure();

        $this->bus()->dispatch(new UpdateMealCommand(mealId: $mealId, date: $this->day('+13 days'), slot: null, recipeIds: null));

        // Three days later, bought three days later — still one pack.
        self::assertSame(['Cabillaud 1 pack'], $this->unitList());
        self::assertSame($this->day('+11 days'), $this->item('Cabillaud')->getBuyAfter()?->format('Y-m-d'));
        $this->assertMercureUpdatePublished(self::GROCERY_TOPIC);
    }

    public function testMovingAMealKeepsTheLinesWhereTheShopperPutThem(): void
    {
        $mealId = $this->planMeal('pasta');
        $this->chooseAll($mealId, 'pasta_product', 'tomato');
        $before = $this->lines();
        $this->resetMercure();

        $this->bus()->dispatch(new UpdateMealCommand(mealId: $mealId, date: $this->day('+3 days'), slot: 'lunch', recipeIds: null));

        // Same rows, same ids, same places, and nothing to publish: neither
        // line keeps, so no date moved.
        self::assertSame($before, $this->lines());
        self::assertSame(0, $this->groceryUpdateCount());
    }

    public function testBringingAPerishableMealForwardMakesItBuyableNow(): void
    {
        $mealId = $this->planMealOn('fish_dish', '+10 days');
        $this->chooseAll($mealId, 'fish');
        self::assertNotNull($this->item('Cabillaud')->getBuyAfter());

        $this->bus()->dispatch(new UpdateMealCommand(mealId: $mealId, date: $this->day(self::TOMORROW), slot: null, recipeIds: null));

        // Two days' keeping and the meal is tomorrow: buy it whenever.
        self::assertNull($this->item('Cabillaud')->getBuyAfter());
    }

    public function testAQuantityTheUserSetByHandSurvivesAMove(): void
    {
        $mealId = $this->planMeal('pasta');
        $this->chooseAll($mealId, 'tomato');

        // The − / + on the list line (MAG-291): the shopper wants ten tomatoes.
        $this->item('Tomate')->setQuantity(10.0);
        $this->em()->flush();

        $this->bus()->dispatch(new UpdateMealCommand(mealId: $mealId, date: $this->day('+3 days'), slot: null, recipeIds: null));
        $this->generate();

        self::assertSame(['Lait' => 1.0, 'Tomate' => 10.0], $this->list());
    }

    public function testADateTheUserSetOnTheirOwnLineIsNotOverwritten(): void
    {
        $this->given('MealGrocerySyncTest.handwritten.yaml');
        $mealId = $this->planMeal('pasta');
        $this->chooseAll($mealId, 'tomato');

        // Their line, their call: not before next week.
        $deferred = new \DateTimeImmutable('+5 days', new \DateTimeZone('Europe/Paris'));
        $this->item('Tomate')->setBuyAfter($deferred);
        $this->em()->flush();

        $this->bus()->dispatch(new UpdateMealCommand(mealId: $mealId, date: $this->day('+3 days'), slot: null, recipeIds: null));

        // Moving a meal that happens to share the line must not drag the
        // line back into today's shopping.
        self::assertSame($deferred->format('Y-m-d'), $this->item('Tomate')->getBuyAfter()?->format('Y-m-d'));
    }

    public function testCancellingAMealThatHasChosenTakesItsLinesBack(): void
    {
        $mealId = $this->planChosenPasta();
        $this->resetMercure();

        $this->bus()->dispatch(new DeleteMealCommand(mealId: $mealId));

        self::assertSame([], $this->list());
        self::assertSame(0, $this->contributionCount());
        $this->assertMercureUpdatePublished(self::GROCERY_TOPIC);
    }

    public function testCancellingAMealThatHasNotChosenLeavesTheListAlone(): void
    {
        $this->planChosenPasta();
        $gratinId = $this->planMeal('gratin');
        $this->resetMercure();

        $this->bus()->dispatch(new DeleteMealCommand(mealId: $gratinId));

        self::assertSame(['Pâtes 1 pack'], $this->unitList());
        self::assertSame(0, $this->groceryUpdateCount(), 'nothing moved, nothing to publish');
    }

    public function testTwoMealsShareOneLineAndEachOnlyTakesBackItsOwnShare(): void
    {
        $pastaId = $this->planMeal('pasta');
        $gratinId = $this->planMeal('gratin');
        $this->chooseAll($pastaId, 'pasta_product', 'tomato');
        $this->chooseAll($gratinId, 'parmesan', 'tomato');

        // One tomato line, not two: 4 for the pasta plus 2 for the gratin.
        self::assertSame(['Parmesan' => 80.0, 'Pâtes' => 400.0, 'Tomate' => 6.0], $this->list());

        $this->bus()->dispatch(new DeleteMealCommand(mealId: $gratinId));

        self::assertSame(['Pâtes' => 400.0, 'Tomate' => 4.0], $this->list());
    }

    public function testALineAlreadyInTheBasketSurvivesTheMealItCameFrom(): void
    {
        $mealId = $this->planMeal('pasta');
        $this->chooseAll($mealId, 'pasta_product', 'tomato');
        $this->check('Tomate');

        $this->bus()->dispatch(new DeleteMealCommand(mealId: $mealId));

        // Bought is bought: taking it off the list would not put it back on
        // the shelf, and the shopper would wonder what happened.
        self::assertSame(['Tomate' => 4.0], $this->list());
    }

    public function testALineTheUserWroteSurvivesTheMealThatJoinedIt(): void
    {
        $this->given('MealGrocerySyncTest.handwritten.yaml');
        $mealId = $this->planMeal('pasta');
        $this->chooseAll($mealId, 'pasta_product', 'tomato');

        // Merged into the line the user already wrote, not added beside it.
        self::assertSame(['Pâtes' => 400.0, 'Tomate' => 4.0], $this->list());
        self::assertSame(GroceryItemSource::Manual, $this->item('Tomate')->getSource());

        $this->bus()->dispatch(new DeleteMealCommand(mealId: $mealId));

        // The user's own line stays, emptied of the meal's share.
        self::assertSame(['Tomate' => null], $this->list());
        self::assertSame(GroceryItemSource::Manual, $this->item('Tomate')->getSource());
    }

    public function testDeletingAMealLineByHandKeepsTheMealAndDropsOnlyThatContribution(): void
    {
        // MAG-283: the shopper deletes « Pâtes » from the list. The line goes
        // with its contribution (no orphan row); the meal and the other line
        // are untouched.
        $mealId = $this->planMeal('pasta');
        $this->chooseAll($mealId, 'pasta_product', 'tomato');
        $pastaId = (string) $this->item('Pâtes')->getId();
        self::assertSame(2, $this->contributionCount());

        $this->bus()->dispatch(new RemoveGroceryItemCommand(
            groceryItemId: $pastaId,
            userId: (string) $this->user()->getId(),
        ));

        self::assertSame(['Tomate' => 4.0], $this->list());
        self::assertSame(1, $this->contributionCount());
        self::assertNotNull($this->em()->getRepository(Meal::class)->find($mealId));
    }

    public function testALineDeletedByHandStaysOffWhenTheMealMoves(): void
    {
        // Nothing derives the meal's lines any more: a move does not put back
        // what the shopper took off (it used to, until MAG-300).
        $mealId = $this->planMeal('pasta');
        $this->chooseAll($mealId, 'pasta_product', 'tomato');
        $this->bus()->dispatch(new RemoveGroceryItemCommand(
            groceryItemId: (string) $this->item('Pâtes')->getId(),
            userId: (string) $this->user()->getId(),
        ));

        $this->bus()->dispatch(new UpdateMealCommand(mealId: $mealId, date: $this->day('+3 days'), slot: 'lunch', recipeIds: null));

        self::assertSame(['Tomate' => 4.0], $this->list());
    }

    public function testTheNeighboursListIsNeverTouched(): void
    {
        $this->given('MealGrocerySyncTest.neighbour.yaml');

        $mealId = $this->planMeal('pasta');
        $this->chooseAll($mealId, 'pasta_product', 'tomato');
        $this->generate();

        $other = $this->getFixture('other_user');
        self::assertSame(['Tomate' => 9.0], $this->list($other->getId()));
    }

    public function testGeneratingAddsNoIngredientOfAMeal(): void
    {
        $this->planMeal('pasta');

        $this->generate();

        // Lait is the recurring item generation brings along, not a meal's.
        self::assertSame(['Lait' => 1.0], $this->list());
    }

    public function testGeneratingLeavesTheLinesAMealChoseAlone(): void
    {
        $this->planChosenPasta();

        $this->generate();
        $this->generate();

        self::assertSame(['Lait 1 l', 'Pâtes 1 pack'], $this->unitList());
    }

    public function testGenerationOnlyAddsRecurringItemsWhoseFrequencyHasRunOut(): void
    {
        $this->generate();

        // The coffee is fortnightly and ten days old, the bleach monthly and
        // three days old. Only the milk, never added, is wanted.
        self::assertSame(['Lait' => 1.0], $this->list());

        $today = new \DateTimeImmutable('today', new \DateTimeZone('Europe/Paris'));
        self::assertSame($today->format('Y-m-d'), $this->recurring('Lait')->getLastAddedAt()?->format('Y-m-d'));
        self::assertNotSame($today->format('Y-m-d'), $this->recurring('Café')->getLastAddedAt()?->format('Y-m-d'));
        self::assertNotSame($today->format('Y-m-d'), $this->recurring('Javel')->getLastAddedAt()?->format('Y-m-d'));
        $this->assertElasticsearchIndexDispatched(RecurringGroceryItem::class);
    }

    public function testAFortnightlyItemComesBackAfterItsFortnight(): void
    {
        $this->recurring('Café')->setLastAddedAt(new \DateTimeImmutable('-15 days'));
        $this->em()->flush();

        $this->generate();

        self::assertSame(['Café' => 1.0, 'Lait' => 1.0], $this->list());
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

    public function testGeneratingRightAfterTheDailyCommandAddsNothingMore(): void
    {
        $this->bus()->dispatch(new AddRecurringGroceryItemCommand((string) $this->recurring('Lait')->getId()));
        self::assertSame(['Lait' => 1.0], $this->list());

        $this->generate();

        self::assertSame(['Lait' => 1.0], $this->list());
    }

    public function testTheDailyCommandRightAfterGenerationAddsNothingMore(): void
    {
        $this->generate();
        self::assertSame(['Lait' => 1.0], $this->list());

        $this->bus()->dispatch(new AddRecurringGroceryItemCommand((string) $this->recurring('Lait')->getId()));

        self::assertSame(['Lait' => 1.0], $this->list());
    }

    public function testRenamingARecipeDoesNotTouchTheList(): void
    {
        $this->planChosenPasta();
        $this->resetMercure();

        $this->bus()->dispatch(new UpdateRecipeCommand(
            recipeId: (string) $this->getFixture('pasta')->getId(),
            name: 'Pâtes sauce tomate',
        ));

        self::assertSame(['Pâtes 1 pack'], $this->unitList());
        self::assertSame(0, $this->groceryUpdateCount());
    }

    public function testDeletingARecipeDeletesTheMealItWasTheOnlyRecipeOfAndTakesItsShareOffTheList(): void
    {
        $pastaMealId = $this->planMeal('pasta');
        $gratinMealId = $this->planMeal('gratin');
        $this->chooseAll($pastaMealId, 'pasta_product', 'tomato');
        $this->chooseAll($gratinMealId, 'parmesan', 'tomato');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->bus()->dispatch(new DeleteRecipeCommand(recipeId: (string) $this->getFixture('pasta')->getId()));

        // The pasta meal goes the way a cancelled meal does; the gratin keeps its own share.
        self::assertSame(['Parmesan' => 80.0, 'Tomate' => 2.0], $this->list());
        self::assertSame(2, $this->contributionCount());
        self::assertSame(1, $this->groceryUpdateCount());
        self::assertNull($this->em()->getRepository(Meal::class)->find($pastaMealId));
        self::assertNotNull($this->em()->getRepository(Meal::class)->find($gratinMealId));
        self::assertNull($this->em()->getRepository(Recipe::class)->find($this->getFixture('pasta')->getId()));

        $this->assertMercureUpdatePublished('/api/meals/'.$pastaMealId);
        $this->assertElasticsearchDeleteDispatched('meals');
    }

    public function testDeletingARecipeKeepsAMealThatHasOtherRecipesAndItsLines(): void
    {
        $mealId = $this->planMeal('pasta');
        $this->replaceRecipes($mealId, 'pasta', 'gratin');
        $this->chooseAll($mealId, 'pasta_product', 'tomato', 'parmesan');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->bus()->dispatch(new DeleteRecipeCommand(recipeId: (string) $this->getFixture('pasta')->getId()));

        // The recipe goes, the list stays as the owner chose it.
        self::assertSame(['Parmesan' => 80.0, 'Pâtes' => 400.0, 'Tomate' => 6.0], $this->list());

        $meal = $this->em()->getRepository(Meal::class)->find($mealId);
        self::assertNotNull($meal);
        self::assertSame(['Gratin de tomates'], $meal->getRecipes()->map(static fn (Recipe $r) => $r->getName())->getValues());
        $this->assertMercureUpdatePublished('/api/meals/'.$mealId);
        $this->assertElasticsearchIndexDispatched(Meal::class);
    }

    public function testDeletingARecipeDeletesItsPastMealsToo(): void
    {
        $upcomingId = $this->planMeal('pasta');
        $pastId = $this->planMealOn('pasta', '-3 days');
        $this->chooseAll($upcomingId, 'pasta_product', 'tomato');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->bus()->dispatch(new DeleteRecipeCommand(recipeId: (string) $this->getFixture('pasta')->getId()));

        // Past or upcoming, a meal that only had this recipe is cancelled, and
        // its shopping with it — as when the owner deletes the meal by hand.
        self::assertNull($this->em()->getRepository(Meal::class)->find($upcomingId));
        self::assertNull($this->em()->getRepository(Meal::class)->find($pastId));
        self::assertSame([], $this->list());
        self::assertSame(0, $this->contributionCount());
        $this->assertMercureUpdatePublished('/api/meals/'.$upcomingId);
        $this->assertMercureUpdatePublished('/api/meals/'.$pastId);
    }

    public function testAMealPlannedOnAPastDayPutsNothingOnTheList(): void
    {
        $this->planMealOn('pasta', '-1 day');

        self::assertSame(0, $this->itemCount());
        self::assertSame(0, $this->contributionCount());
    }

    public function testMovingAMealToAPastDayTakesItsUnboughtShareOffTheList(): void
    {
        $mealId = $this->planMeal('pasta');
        $gratinId = $this->planMealOn('gratin', '+2 days');
        $this->chooseAll($mealId, 'pasta_product', 'tomato');
        $this->chooseAll($gratinId, 'tomato');
        $this->check('Pâtes');
        $this->resetMercure();

        $this->bus()->dispatch(new UpdateMealCommand(mealId: $mealId, date: $this->day('-1 day'), slot: null, recipeIds: null));

        // The pasta is already in the basket; the tomatoes are the gratin's alone now.
        self::assertSame(['Pâtes' => 400.0, 'Tomate' => 2.0], $this->list());
        $this->assertMercureUpdatePublished(self::GROCERY_TOPIC);
    }

    public function testTwoMealsOnOnePackagedLineAddUpTheirPackagingsAndGiveThemBack(): void
    {
        $this->givePackaging('pasta_product', 500);
        $first = $this->planMeal('pasta');
        $second = $this->planMealOn('pasta', '+2 days');
        $this->chooseAll($first, 'pasta_product');
        $this->chooseAll($second, 'pasta_product');

        self::assertSame(['Pâtes 2 pack'], $this->unitList());

        $this->bus()->dispatch(new DeleteMealCommand(mealId: $second));

        self::assertSame(['Pâtes 1 pack'], $this->unitList());
    }

    /** Plans tomorrow's pasta, then chooses only the pasta — 400 g, 1 pack of 500 g. */
    private function planChosenPasta(): string
    {
        $this->givePackaging('pasta_product', 500);
        $mealId = $this->planMeal('pasta');
        $this->chooseAll($mealId, 'pasta_product');
        self::assertSame(['Pâtes 1 pack'], $this->unitList());

        return $mealId;
    }

    private function givePackaging(string $ingredientRef, float $grams): void
    {
        $ingredient = $this->em()->find(Ingredient::class, $this->getFixture($ingredientRef)->getId())
            ?? throw new \LogicException("No ingredient {$ingredientRef}.");
        $ingredient->setPackagingUnit(Unit::Pack);
        $ingredient->setPackagingSize($grams);
        $ingredient->setPackagingSizeUnit(Unit::Gram);
        $this->em()->flush();
        $this->em()->clear();
    }

    private function chooseAll(string $mealId, string ...$ingredientRefs): void
    {
        $this->bus()->dispatch(new ChooseMealGroceriesCommand(
            mealId: $mealId,
            userId: (string) $this->user()->getId(),
            chosen: array_fill_keys(array_map(fn (string $ref) => (string) $this->getFixture($ref)->getId(), $ingredientRefs), null),
        ));
        $this->em()->clear();
    }

    private function day(string $when): string
    {
        return (new \DateTimeImmutable($when, new \DateTimeZone('Europe/Paris')))->format('Y-m-d');
    }

    /**
     * The list as « label quantity unit », sorted — the unit is what tells a
     * chosen line from a derived one.
     *
     * @return list<string>
     */
    private function unitList(): array
    {
        $this->em()->clear();

        $lines = [];
        foreach ($this->groceryList()->getItems() as $item) {
            $lines[] = $item->getLabel().' '.$item->getQuantity().' '.$item->getUnit()?->value;
        }
        sort($lines);

        return $lines;
    }

    /**
     * Rewrites a fixture recipe's ingredients.
     *
     * @param list<array{0: string, 1: float|int, 2: string}> $lines fixture ref, quantity, unit
     */
    private function editRecipe(string $recipeRef, array $lines): void
    {
        $this->bus()->dispatch(new UpdateRecipeCommand(
            recipeId: (string) $this->getFixture($recipeRef)->getId(),
            ingredients: array_map(fn (array $line) => [
                'ingredientId' => (string) $this->getFixture($line[0])->getId(),
                'quantity' => (float) $line[1],
                'unit' => $line[2],
            ], $lines),
        ));
    }

    /** Plans tomorrow's dinner from one fixture recipe and returns its id. */
    private function planMeal(string $recipeRef): string
    {
        return $this->planMealOn($recipeRef, self::TOMORROW);
    }

    private function planMealOn(string $recipeRef, string $when): string
    {
        $envelope = $this->bus()->dispatch(new CreateMealCommand(
            date: (new \DateTimeImmutable($when, new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: 'dinner',
            recipeIds: [(string) $this->getFixture($recipeRef)->getId()],
            userId: (string) $this->user()->getId(),
        ));

        return (string) $envelope->last(HandledStamp::class)->getResult()->getId();
    }

    private function replaceRecipes(string $mealId, string ...$recipeRefs): void
    {
        $this->bus()->dispatch(new UpdateMealCommand(
            mealId: $mealId,
            date: null,
            slot: null,
            recipeIds: array_map(fn (string $ref) => (string) $this->getFixture($ref)->getId(), $recipeRefs),
        ));
    }

    private function generate(string $from = self::TOMORROW, string $to = self::TOMORROW): void
    {
        $this->bus()->dispatch(new GenerateGroceryListCommand(
            userId: (string) $this->user()->getId(),
            fromDate: $this->day($from),
            toDate: $this->day($to),
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
    private function list(?object $userId = null): array
    {
        $this->em()->clear();

        $items = [];
        foreach ($this->groceryList($userId)->getItems() as $item) {
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

    /**
     * Identity and place of each line — what a delete-and-recreate destroys.
     *
     * @return array<string, array{id: string, position: int}>
     */
    private function lines(): array
    {
        $this->em()->clear();

        $lines = [];
        foreach ($this->groceryList()->getItems() as $item) {
            $lines[$item->getLabel()] = ['id' => (string) $item->getId(), 'position' => $item->getPosition()];
        }
        ksort($lines);

        return $lines;
    }

    private function item(string $label): GroceryItem
    {
        $this->em()->clear();

        foreach ($this->groceryList()->getItems() as $item) {
            if ($item->getLabel() === $label) {
                return $item;
            }
        }

        throw new \LogicException("No grocery line labelled {$label}.");
    }

    private function groceryList(?object $userId = null): GroceryList
    {
        $criteria = null !== $userId ? ['user' => $userId] : [];

        return $this->em()->getRepository(GroceryList::class)->findOneBy($criteria)
            ?? throw new \LogicException('No grocery list was created.');
    }

    /** How many Mercure updates went out on the grocery list's own topic. */
    private function groceryUpdateCount(): int
    {
        $count = 0;

        foreach ($this->getMercureHub()->getUpdates() as $update) {
            foreach ($update->getTopics() as $topic) {
                if (str_contains($topic, self::GROCERY_TOPIC)) {
                    ++$count;
                    break;
                }
            }
        }

        return $count;
    }

    private function recurring(string $label): RecurringGroceryItem
    {
        return $this->em()->getRepository(RecurringGroceryItem::class)->findOneBy(['customLabel' => $label])
            ?? throw new \LogicException("No recurring item labelled {$label}.");
    }

    private function itemCount(): int
    {
        return count($this->em()->getRepository(GroceryItem::class)->findAll());
    }

    private function contributionCount(): int
    {
        return count($this->em()->getRepository(MealGroceryContribution::class)->findAll());
    }

    private function user(): User
    {
        return $this->em()->getRepository(User::class)->findOneBy(['email' => 'cookbook-test@example.com'])
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
