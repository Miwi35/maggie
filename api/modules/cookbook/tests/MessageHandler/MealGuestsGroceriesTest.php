<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\MealGroceryContribution;
use Maggie\Cookbook\Message\ChooseMealGroceriesCommand;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Cookbook\Message\UpdateMealCommand;
use Maggie\Cookbook\Message\UpdateMealSettingsCommand;
use Maggie\Cookbook\Service\MealGroceryChoice;
use Maggie\Cookbook\UseCase\GetMealSettings;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The guests of a meal scale what its recipes put on the grocery list
 * (MAG-482): a recipe for 4 with 400 g of pasta needs 600 g for 6 and 200 g
 * for 2; a meal that names no guests is cooked for the user's default.
 * Changing the guests, or the default, rescales what the meals hold.
 */
class MealGuestsGroceriesTest extends KernelTestCase
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
        $this->loadFixtures('MealGuestsTest.yaml');
        $this->loginFixtureUser();
        $this->em()->clear();
    }

    public function testSixGuestsOfARecipeForFourNeedOneAndAHalfTimesItsQuantities(): void
    {
        $this->chooseAll($this->planMeal('pasta', 6), 'pasta_product');

        self::assertSame(['Pâtes' => 600.0], $this->list());
    }

    public function testTwoGuestsOfARecipeForFourNeedHalfItsQuantities(): void
    {
        $this->chooseAll($this->planMeal('pasta', 2), 'pasta_product');

        self::assertSame(['Pâtes' => 200.0], $this->list());
    }

    public function testAMealWithoutGuestsIsCookedForTheDefaultOfTwo(): void
    {
        $this->chooseAll($this->planMeal('pasta', null), 'pasta_product');

        self::assertSame(['Pâtes' => 200.0], $this->list());
    }

    public function testChangingTheGuestsOfAMealRescalesItsNeedsAndPublishesTheList(): void
    {
        $mealId = $this->planMeal('pasta', 6);
        $this->chooseAll($mealId, 'pasta_product');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->bus()->dispatch(new UpdateMealCommand(mealId: $mealId, guests: 2));

        self::assertSame(['Pâtes' => 200.0], $this->list());
        self::assertSame(200.0, $this->heldBy($mealId));
        $stored = $this->em()->find(Meal::class, $mealId);
        self::assertSame(2, $stored?->getOwnGuests());
        $this->assertMercureUpdatePublished('/api/grocery_lists/');
        $this->assertMercureUpdatePublished('/meals/');
        $payloads = $this->mercurePayloadsOn('/api/meals/'.$mealId);
        self::assertSame(2, end($payloads)['guests']);
        self::assertFalse(end($payloads)['guestsIsDefault']);
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
        $this->assertElasticsearchIndexDispatchedFor(Meal::class, $mealId);
    }

    public function testGivingAMealBackToTheDefaultRescalesItToTheDefault(): void
    {
        $mealId = $this->planMeal('pasta', 6);
        $this->chooseAll($mealId, 'pasta_product');

        $this->bus()->dispatch(new UpdateMealCommand(mealId: $mealId, useDefaultGuests: true));

        self::assertSame(['Pâtes' => 200.0], $this->list());
        self::assertNull($this->em()->find(Meal::class, $mealId)?->getOwnGuests());
    }

    public function testAPackagedLineCountsItsPacksOnTheScaledNeed(): void
    {
        // 300 g for 4 is 600 g for 8: two 500 g packs, then one again for 4.
        $mealId = $this->planMeal('curry', 8);
        $this->chooseAll($mealId, 'rice');
        self::assertSame(['Riz' => 2.0], $this->list());

        $this->bus()->dispatch(new UpdateMealCommand(mealId: $mealId, guests: 4));

        self::assertSame(['Riz' => 1.0], $this->list());
        self::assertSame(300.0, $this->heldBy($mealId));
    }

    public function testPackagingsTheOwnerForcedAreKeptWhenTheGuestsChange(): void
    {
        $mealId = $this->planMeal('curry', 4);
        $this->bus()->dispatch(new ChooseMealGroceriesCommand(
            mealId: $mealId,
            userId: (string) $this->user()->getId(),
            chosen: [(string) $this->getFixture('rice')->getId() => 3.0],
        ));

        $this->bus()->dispatch(new UpdateMealCommand(mealId: $mealId, guests: 8));

        self::assertSame(['Riz' => 3.0], $this->list());
    }

    public function testChangingTheDefaultRescalesTheUpcomingMealsThatFollowItOnly(): void
    {
        $onDefault = $this->planMeal('pasta', null);
        $ownGuests = $this->planMeal('pasta', 6);
        $this->chooseAll($onDefault, 'pasta_product');
        $this->chooseAll($ownGuests, 'pasta_product');
        self::assertSame(['Pâtes' => 800.0], $this->list());

        $this->changeDefaultGuests(3);

        self::assertSame(300.0, $this->heldBy($onDefault));
        self::assertSame(600.0, $this->heldBy($ownGuests), 'a meal with its own guests keeps them');
        self::assertSame(['Pâtes' => 900.0], $this->list());
    }

    public function testThePreviewOfAMealSaysTheGuestsItIsScaledTo(): void
    {
        $mealId = $this->planMeal('pasta', null);
        $this->changeDefaultGuests(8);

        $envelope = $this->bus()->dispatch(new UpdateMealCommand(mealId: $mealId));
        $meal = $envelope->last(HandledStamp::class)?->getResult();
        self::assertInstanceOf(Meal::class, $meal);
        $preview = self::getContainer()->get(MealGroceryChoice::class)->preview($meal);

        self::assertSame(8, $preview['guests']);
        self::assertTrue($preview['guestsIsDefault']);
        self::assertSame(800.0, $preview['ingredients'][0]['quantity']);
    }

    public function testAMealCannotBeCookedForNobody(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessageMatches('/at least one guest/');

        $this->planMeal('pasta', 0);
    }

    private function changeDefaultGuests(int $defaultGuests): void
    {
        $settings = self::getContainer()->get(GetMealSettings::class)->forUser($this->user());
        $this->bus()->dispatch(new UpdateMealSettingsCommand(
            userId: (string) $this->user()->getId(),
            mealSettingsId: (string) $settings->getId(),
            defaultGuests: $defaultGuests,
        ));
        $this->em()->clear();
    }

    private function planMeal(string $recipeRef, ?int $guests): string
    {
        $envelope = $this->bus()->dispatch(new CreateMealCommand(
            date: (new \DateTimeImmutable('+1 day', new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: 'dinner',
            recipeIds: [(string) $this->getFixture($recipeRef)->getId()],
            userId: (string) $this->user()->getId(),
            guests: $guests,
        ));
        $this->em()->clear();

        return (string) $envelope->last(HandledStamp::class)?->getResult()->getId();
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

    /** @return array<string, float|null> label => quantity */
    private function list(): array
    {
        $this->em()->clear();

        $items = [];
        foreach ($this->em()->getRepository(GroceryItem::class)->findAll() as $item) {
            $items[$item->getLabel()] = $item->getQuantity();
        }
        ksort($items);

        return $items;
    }

    /** What the meal holds on the list, in base units. */
    private function heldBy(string $mealId): float
    {
        $this->em()->clear();

        $total = 0.0;
        foreach ($this->em()->getRepository(MealGroceryContribution::class)->findBy(['meal' => $mealId]) as $contribution) {
            $total += $contribution->getQuantity();
        }

        return $total;
    }

    private function user(): User
    {
        $user = $this->em()->find(User::class, $this->getFixture('test_user')->getId());
        \assert($user instanceof User);

        return $user;
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
