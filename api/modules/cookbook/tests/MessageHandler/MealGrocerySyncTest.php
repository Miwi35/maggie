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
use Maggie\Grocery\Message\RemoveGroceryItemCommand;
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

    public function testPlanningAMealPutsItsIngredientsOnTheListAndPublishesIt(): void
    {
        $this->planMeal('pasta');

        self::assertSame(['Pâtes' => 400.0, 'Tomate' => 4.0], $this->list());

        $this->assertMercureUpdatePublished(self::GROCERY_TOPIC);
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testTheListIsPublishedOnceAndOnlyOnce(): void
    {
        // The broadcaster exists because the middlewares only see the
        // handler's return value; calling both would publish twice, and the
        // admin would rerender the whole list for nothing.
        $this->planMeal('pasta');

        self::assertSame(1, $this->groceryUpdateCount(), 'the grocery list must be published exactly once');
    }

    public function testSwappingARecipeTakesTheOldIngredientsOffTheList(): void
    {
        $mealId = $this->planMeal('pasta');
        $this->resetMercure();

        $this->replaceRecipes($mealId, 'gratin');

        // The pasta is gone, the tomatoes are down to what the gratin needs.
        self::assertSame(['Parmesan' => 80.0, 'Tomate' => 2.0], $this->list());
        $this->assertMercureUpdatePublished(self::GROCERY_TOPIC);
    }

    public function testMovingAMealKeepsTheLineWhereTheShopperPutIt(): void
    {
        $mealId = $this->planMeal('pasta');
        $before = $this->lines();

        // Nothing about the shopping changes — only the day.
        $this->bus()->dispatch(new UpdateMealCommand(
            mealId: $mealId,
            date: (new \DateTimeImmutable('+3 days', new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: 'lunch',
            recipeIds: null,
        ));

        // Same rows, same ids, same places: a line deleted and re-created
        // would lose the order the shopper gave it, and flicker on screen.
        self::assertSame($before, $this->lines());
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
        $this->assertMercureUpdatePublished(self::GROCERY_TOPIC);
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

    public function testALineTheUserWroteSurvivesTheMealThatJoinedIt(): void
    {
        $this->given('MealGrocerySyncTest.handwritten.yaml');

        $mealId = $this->planMeal('pasta');

        // Merged into the line the user already wrote, not added beside it.
        self::assertSame(['Pâtes' => 400.0, 'Tomate' => 4.0], $this->list());
        self::assertSame(GroceryItemSource::Manual, $this->item('Tomate')->getSource());

        $this->bus()->dispatch(new DeleteMealCommand(mealId: $mealId));

        // The user's own line stays, emptied of the meal's share.
        self::assertSame(['Tomate' => null], $this->list());
        self::assertSame(GroceryItemSource::Manual, $this->item('Tomate')->getSource());
    }

    public function testALineWhoseUnitTheUserCorrectedIsLetGoRatherThanJoinedTwice(): void
    {
        // Two meals on one tomato line, so taking the first one's share back
        // leaves the line standing — which is what made this go wrong.
        $pastaId = $this->planMeal('pasta');
        $this->planMeal('gratin');
        self::assertSame(6.0, $this->item('Tomate')->getQuantity());

        // The shopper buys tomatoes by weight, not by the piece, and fixes
        // both the line and the recipe.
        $this->item('Tomate')->setUnit(Unit::Kilogram);
        foreach ($this->recipe('pasta')->getIngredients() as $ri) {
            if ('Tomate' === $ri->getIngredient()->getName()) {
                $ri->setUnit(Unit::Kilogram);
            }
        }
        $this->em()->flush();

        // The meal holds that line under the old unit and wants it under the
        // new one. Re-joining the line it had just let go used to insert a
        // second contribution for the same pair while the first one's delete
        // was still pending — straight into the unique index, 500.
        $this->bus()->dispatch(new UpdateMealCommand(mealId: $pastaId, date: null, slot: null, recipeIds: null));

        // Pâtes, Parmesan, the gratin's two tomatoes left on the old line,
        // and the pasta's four on a new one.
        self::assertCount(4, $this->groceryList()->getItems());
        self::assertSame([2.0, 4.0], $this->quantitiesOf('Tomate'));
        self::assertSame(4, $this->contributionCount());
    }

    public function testADateTheUserSetOnTheirOwnLineIsNotOverwritten(): void
    {
        $this->given('MealGrocerySyncTest.handwritten.yaml');
        $mealId = $this->planMeal('pasta');

        // Their line, their call: not before next week.
        $deferred = new \DateTimeImmutable('+5 days', new \DateTimeZone('Europe/Paris'));
        $this->item('Tomate')->setBuyAfter($deferred);
        $this->em()->flush();

        $this->bus()->dispatch(new UpdateMealCommand(
            mealId: $mealId,
            date: (new \DateTimeImmutable('+3 days', new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: null,
            recipeIds: null,
        ));

        // Moving a meal that happens to share the line must not drag the
        // line back into today's shopping.
        self::assertSame($deferred->format('Y-m-d'), $this->item('Tomate')->getBuyAfter()?->format('Y-m-d'));
    }

    public function testAPerishableIsNotToBeBoughtBeforeItKeeps(): void
    {
        $this->planMealOn('fish_dish', '+10 days');

        $expected = (new \DateTimeImmutable('+8 days', new \DateTimeZone('Europe/Paris')))->format('Y-m-d');
        self::assertSame($expected, $this->item('Cabillaud')->getBuyAfter()?->format('Y-m-d'));
    }

    public function testBringingAPerishableMealForwardMakesItBuyableNow(): void
    {
        $mealId = $this->planMealOn('fish_dish', '+10 days');
        self::assertNotNull($this->item('Cabillaud')->getBuyAfter());

        $this->bus()->dispatch(new UpdateMealCommand(
            mealId: $mealId,
            date: (new \DateTimeImmutable(self::TOMORROW, new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: null,
            recipeIds: null,
        ));

        // Two days' keeping and the meal is tomorrow: buy it whenever.
        self::assertNull($this->item('Cabillaud')->getBuyAfter());
    }

    public function testTheNeighboursListIsNeverTouched(): void
    {
        $this->given('MealGrocerySyncTest.neighbour.yaml');

        $this->planMeal('pasta');
        $this->generate();

        $other = $this->getFixture('other_user');
        self::assertSame(['Tomate' => 9.0], $this->list($other->getId()));
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

    public function testChangingAnIngredientQuantityUpdatesTheLineOfUpcomingMeals(): void
    {
        $this->planMeal('pasta');
        $before = $this->lines();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->editRecipe('pasta', [['pasta_product', 600, 'g'], ['tomato', 4, 'piece']]);

        // Same line, same place, new quantity.
        self::assertSame(['Pâtes' => 600.0, 'Tomate' => 4.0], $this->list());
        self::assertSame($before, $this->lines());
        self::assertSame(1, $this->groceryUpdateCount());
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testDroppingAnIngredientFromARecipeTakesItsLineOffTheList(): void
    {
        $this->planMeal('pasta');

        $this->editRecipe('pasta', [['tomato', 4, 'piece']]);

        self::assertSame(['Tomate' => 4.0], $this->list());
    }

    public function testDroppingAnIngredientAnotherMealStillNeedsKeepsItsShare(): void
    {
        $this->planMeal('pasta');
        $this->planMeal('gratin');

        $this->editRecipe('pasta', [['pasta_product', 400, 'g']]);

        self::assertSame(['Parmesan' => 80.0, 'Pâtes' => 400.0, 'Tomate' => 2.0], $this->list());
    }

    public function testEditingARecipeLeavesPastMealsAlone(): void
    {
        $this->planMealOn('pasta', '-3 days');

        $this->editRecipe('pasta', [['pasta_product', 600, 'g'], ['tomato', 4, 'piece']]);

        self::assertSame(['Pâtes' => 400.0, 'Tomate' => 4.0], $this->list());
    }

    public function testEditingARecipeLeavesALineAlreadyInTheBasketAlone(): void
    {
        $this->planMeal('pasta');
        $this->check('Pâtes');

        $this->editRecipe('pasta', [['pasta_product', 600, 'g'], ['tomato', 4, 'piece']]);

        self::assertSame(['Pâtes' => 400.0, 'Tomate' => 4.0], $this->list());
    }

    public function testRenamingARecipeDoesNotTouchTheList(): void
    {
        $this->planMeal('pasta');
        $this->resetMercure();

        $this->bus()->dispatch(new UpdateRecipeCommand(
            recipeId: (string) $this->getFixture('pasta')->getId(),
            name: 'Pâtes sauce tomate',
        ));

        self::assertSame(['Pâtes' => 400.0, 'Tomate' => 4.0], $this->list());
        self::assertSame(0, $this->groceryUpdateCount());
    }

    public function testDeletingARecipeDeletesTheMealItWasTheOnlyRecipeOfAndTakesItsShareOffTheList(): void
    {
        $pastaMealId = $this->planMeal('pasta');
        $gratinMealId = $this->planMeal('gratin');
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

    public function testDeletingARecipeKeepsAMealThatHasOtherRecipesAndRecomputesItsShare(): void
    {
        $mealId = $this->planMeal('pasta');
        $this->replaceRecipes($mealId, 'pasta', 'gratin');
        $this->resetMercure();
        $this->resetAsyncTransport();

        self::assertSame(['Parmesan' => 80.0, 'Pâtes' => 400.0, 'Tomate' => 6.0], $this->list());

        $this->bus()->dispatch(new DeleteRecipeCommand(recipeId: (string) $this->getFixture('pasta')->getId()));

        self::assertSame(['Parmesan' => 80.0, 'Tomate' => 2.0], $this->list());

        $meal = $this->em()->getRepository(Meal::class)->find($mealId);
        self::assertNotNull($meal);
        self::assertSame(['Gratin de tomates'], $meal->getRecipes()->map(static fn (Recipe $r) => $r->getName())->getValues());
        $this->assertMercureUpdatePublished('/api/meals/'.$mealId);
        $this->assertElasticsearchIndexDispatched(Meal::class);
    }

    public function testEditingARecipeAlsoUpdatesTodaysMeal(): void
    {
        // Midnight in Paris is the day before in UTC: a meal planned for today
        // is not a past meal, whatever the database's own time zone.
        $this->planMealOn('pasta', 'today');

        $this->editRecipe('pasta', [['pasta_product', 600, 'g'], ['tomato', 4, 'piece']]);

        self::assertSame(['Pâtes' => 600.0, 'Tomate' => 4.0], $this->list());
    }

    public function testDeletingARecipeDeletesItsPastMealsToo(): void
    {
        $upcomingId = $this->planMeal('pasta');
        $pastId = $this->planMealOn('pasta', '-3 days');
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

    public function testDeletingARecipeLeavesThePastShoppingOfAMealThatKeepsOtherRecipes(): void
    {
        $upcomingId = $this->planMeal('pasta');
        $pastId = $this->planMealOn('pasta', '-3 days');
        $this->replaceRecipes($upcomingId, 'pasta', 'gratin');
        $this->replaceRecipes($pastId, 'pasta', 'gratin');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->bus()->dispatch(new DeleteRecipeCommand(recipeId: (string) $this->getFixture('pasta')->getId()));

        // The upcoming meal's pasta share goes; the past meal's stays — that
        // shopping was done.
        self::assertSame(['Parmesan' => 160.0, 'Pâtes' => 400.0, 'Tomate' => 8.0], $this->list());
        self::assertCount(1, $this->em()->getRepository(Meal::class)->find($upcomingId)->getRecipes());
        self::assertCount(1, $this->em()->getRepository(Meal::class)->find($pastId)->getRecipes());
        $this->assertMercureUpdatePublished('/api/meals/'.$upcomingId);
        $this->assertElasticsearchIndexDispatchedFor(Meal::class, $pastId);
    }

    public function testDeletingAMealLineByHandKeepsTheMealAndDropsOnlyThatContribution(): void
    {
        // MAG-283: the shopper deletes « Pâtes » from the list. The line goes
        // with its contribution (no orphan row); the meal and the other line
        // are untouched.
        $mealId = $this->planMeal('pasta');
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

    public function testALineDeletedByHandComesBackWhenTheMealIsSyncedAgain(): void
    {
        // The behaviour the admin's confirmation explains (MAG-283): nothing
        // remembers a deletion, so the meal's next sync puts the line back.
        $mealId = $this->planMeal('pasta');
        $this->bus()->dispatch(new RemoveGroceryItemCommand(
            groceryItemId: (string) $this->item('Pâtes')->getId(),
            userId: (string) $this->user()->getId(),
        ));
        self::assertSame(['Tomate' => 4.0], $this->list());

        // Any update of the meal syncs it, and the meal still needs the pasta.
        $this->bus()->dispatch(new UpdateMealCommand(
            mealId: $mealId,
            date: (new \DateTimeImmutable('+3 days', new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: 'lunch',
            recipeIds: null,
        ));
        self::assertSame(['Pâtes' => 400.0, 'Tomate' => 4.0], $this->list());
    }

    public function testAMealThatHasChosenIsNotDerivedAgainWhenItsRecipesChange(): void
    {
        // MAG-295: the chosen line is in packs, the recipe asks grams. A sync
        // deriving from the recipe would never want the pack, and would take
        // it back in silence.
        $mealId = $this->planChosenPasta();

        $this->replaceRecipes($mealId, 'pasta', 'gratin');

        self::assertSame(['Pâtes 1 pack'], $this->unitList());
    }

    public function testAMealThatHasChosenIsNotDerivedAgainWhenOneOfItsRecipesIsEdited(): void
    {
        $this->planChosenPasta();
        $this->resetMercure();

        $this->editRecipe('pasta', [['pasta_product', 600, 'g'], ['tomato', 4, 'piece'], ['parmesan', 50, 'g']]);

        self::assertSame(['Pâtes 1 pack'], $this->unitList());
        self::assertSame(0, $this->groceryUpdateCount(), 'nothing moved, nothing to publish');
    }

    public function testAMealThatHasChosenIsNotDerivedAgainByGeneration(): void
    {
        $this->planChosenPasta();

        $this->generate();

        self::assertSame(['Lait 1 l', 'Pâtes 1 pack'], $this->unitList());
    }

    public function testPlanningAnotherMealLeavesTheChosenLinesAlone(): void
    {
        $this->planChosenPasta();

        // A meal that has not chosen still derives, on lines of its own: grams
        // do not join a line in packs.
        $this->planMeal('pasta');

        self::assertSame(['Pâtes 1 pack', 'Pâtes 400 g', 'Tomate 4 piece'], $this->unitList());
        self::assertSame(3, $this->contributionCount());
    }

    public function testMovingAMealThatHasChosenMovesTheBuyAfterOfItsLinesAndNotTheirQuantity(): void
    {
        $this->givePackaging('fish', 400);
        $mealId = $this->planMealOn('fish_dish', '+10 days');
        $this->chooseAll($mealId, 'fish');
        self::assertSame(['Cabillaud 1 pack'], $this->unitList());
        self::assertSame($this->day('+8 days'), $this->item('Cabillaud')->getBuyAfter()?->format('Y-m-d'));

        $this->bus()->dispatch(new UpdateMealCommand(mealId: $mealId, date: $this->day('+13 days'), slot: null, recipeIds: null));

        // Three days later, bought three days later — still one pack.
        self::assertSame(['Cabillaud 1 pack'], $this->unitList());
        self::assertSame($this->day('+11 days'), $this->item('Cabillaud')->getBuyAfter()?->format('Y-m-d'));
        $this->assertMercureUpdatePublished(self::GROCERY_TOPIC);
    }

    public function testCancellingAMealThatHasChosenStillTakesItsLinesBack(): void
    {
        $mealId = $this->planChosenPasta();

        $this->bus()->dispatch(new DeleteMealCommand(mealId: $mealId));

        self::assertSame([], $this->list());
        self::assertSame(0, $this->contributionCount());
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

    /**
     * Every quantity carried under one label, sorted — the way to speak about
     * a product that sits on more than one line.
     *
     * @return float[]
     */
    private function quantitiesOf(string $label): array
    {
        $this->em()->clear();

        $quantities = [];
        foreach ($this->groceryList()->getItems() as $item) {
            if ($item->getLabel() === $label) {
                $quantities[] = $item->getQuantity();
            }
        }
        sort($quantities);

        return $quantities;
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

    private function recipe(string $ref): Recipe
    {
        return $this->em()->getRepository(Recipe::class)->find($this->getFixture($ref)->getId())
            ?? throw new \LogicException("No recipe {$ref}.");
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
