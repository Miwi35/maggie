<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Service;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\MealGroceryContribution;
use Maggie\Cookbook\Message\ChooseMealGroceriesCommand;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Cookbook\Service\MealGroceryChoice;
use Maggie\Cookbook\Service\MealGrocerySync;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\Unit;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Choosing which ingredients of a meal go on the list, in packagings (MAG-295).
 *
 * The meal is planned the way it is today — its recipes put their ingredients
 * on the list in recipe units — and then chosen: the derived lines must give
 * way to the chosen ones, never sit beside them.
 */
class MealGroceryChoiceTest extends KernelTestCase
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
        $this->loadFixtures('MealGroceryChoiceTest.yaml');
        $this->loginFixtureUser();
        // Alice builds each recipe's ingredients from the other side: detached,
        // the services read what the database holds.
        $this->em()->clear();
    }

    public function testThePreviewRoundsTheRecipeQuantityUpToPackagings(): void
    {
        $rice = $this->previewOf($this->planMeal('curry'), 'Riz');

        self::assertSame(300.0, $rice['quantity']);
        self::assertSame('g', $rice['unit']);
        self::assertSame(['unit' => 'pack', 'size' => 500.0, 'sizeUnit' => 'g'], $rice['packaging']);
        self::assertSame(1, $rice['packagedQuantity']);
        self::assertSame(['quantity' => 1, 'unit' => 'pack'], $rice['toBuy']);
        self::assertTrue($rice['converted']);
    }

    public function testThePreviewOfTheFirstMealFindsNothingOnTheListYet(): void
    {
        $rice = $this->previewOf($this->planMeal('curry'), 'Riz');

        self::assertNull($rice['onList'], 'the only need on the line is this meal\'s own');
        self::assertSame([], $rice['otherMeals']);
        self::assertSame(
            ['needed' => [['quantity' => 300.0, 'unit' => 'g']], 'packagesTotal' => 1, 'packagesToAdd' => 1],
            $rice['withThisMeal'],
        );
    }

    public function testThePreviewOfASecondMealSaysWhatTheListAlreadyHoldsAndTheTotalInPackagings(): void
    {
        $this->choose($this->planMeal('curry'), ['rice']);
        $second = $this->planMealOn('big_rice', '+2 days');

        $rice = $this->previewOf($second, 'Riz');

        // 300 g of the curry are planned; with 500 g, 800 g is two packs: one more.
        self::assertSame(['needed' => [['quantity' => 300.0, 'unit' => 'g']], 'packages' => 1], $rice['onList']);
        self::assertCount(1, $rice['otherMeals']);
        self::assertSame(300.0, $rice['otherMeals'][0]['quantity']);
        self::assertSame('g', $rice['otherMeals'][0]['unit']);
        self::assertStringContainsString('curry', $rice['otherMeals'][0]['name']);
        self::assertSame(
            ['needed' => [['quantity' => 800.0, 'unit' => 'g']], 'packagesTotal' => 2, 'packagesToAdd' => 1],
            $rice['withThisMeal'],
        );
    }

    public function testThePreviewAnswersTheSameBeforeAndAfterTheMealJoinsTheList(): void
    {
        $this->choose($this->planMeal('curry'), ['rice']);
        $second = $this->planMealOn('big_rice', '+2 days');

        $before = $this->previewOf($second, 'Riz');
        $this->choose($second, ['rice']);
        $after = $this->previewOf($second, 'Riz');

        self::assertSame($before['onList'], $after['onList']);
        self::assertSame($before['withThisMeal'], $after['withThisMeal']);
        self::assertSame(2, $after['withThisMeal']['packagesTotal']);
    }

    public function testChoosingTheSecondMealAddsTheMissingPackagingTheSamePreviewAnnounced(): void
    {
        $this->choose($this->planMeal('curry'), ['rice']);
        $second = $this->planMealOn('big_rice', '+2 days');
        $announced = $this->previewOf($second, 'Riz')['withThisMeal']['packagesTotal'];

        $this->choose($second, ['rice']);

        $rice = array_values(array_filter($this->list(), static fn (array $line) => 'Riz' === $line[0]));
        self::assertSame([['Riz', (float) $announced, 'pack']], $rice);
    }

    public function testOnlyWhatIsRunningLowIsSuggested(): void
    {
        $mealId = $this->planMeal('curry');

        self::assertTrue($this->previewOf($mealId, 'Riz')['suggested'], 'out of stock');
        self::assertSame('out', $this->previewOf($mealId, 'Riz')['stockState']);
        self::assertTrue($this->previewOf($mealId, 'Oignon')['suggested'], 'running low');
        self::assertFalse($this->previewOf($mealId, 'Légumes pour couscous')['suggested'], 'in the cupboard');
        self::assertSame('in_stock', $this->previewOf($mealId, 'Légumes pour couscous')['stockState']);
    }

    public function testAnIngredientWithoutPackagingIsAnnouncedInTheRecipeUnit(): void
    {
        $onion = $this->previewOf($this->planMeal('curry'), 'Oignon');

        self::assertNull($onion['packaging']);
        self::assertNull($onion['packagedQuantity']);
        self::assertSame(['quantity' => 2.0, 'unit' => 'piece'], $onion['toBuy']);
    }

    public function testThePreviewSaysWhetherAChoiceWasMade(): void
    {
        $mealId = $this->planMeal('curry');
        self::assertNull($this->preview($mealId)['groceryChoiceMadeAt']);

        $this->choose($mealId, ['rice']);

        self::assertNotNull($this->preview($mealId)['groceryChoiceMadeAt']);
    }

    public function testChoosingPutsTheChosenLinesOnTheListInPackagings(): void
    {
        $mealId = $this->planMeal('curry');
        // Planning puts nothing on the list (MAG-300).
        self::assertSame([], $this->list());

        $this->choose($mealId, ['rice']);

        // « Riz — 1 paquet », and not « Riz — 300 g » beside it.
        self::assertSame([['Riz', 1.0, 'pack']], $this->list());
        self::assertSame([[300.0, 'g']], $this->contributions());
        self::assertSame(GroceryItemSource::Recipe, $this->item('Riz')->getSource());
    }

    public function testTheChoiceIsStampedOnTheMeal(): void
    {
        $mealId = $this->planMeal('curry');

        $this->choose($mealId, ['rice']);

        self::assertNotNull($this->meal($mealId)->getGroceryChoiceMadeAt());
    }

    public function testChoosingNothingTakesEverythingBackAndStillStampsTheChoice(): void
    {
        $mealId = $this->planMeal('curry');

        $this->choose($mealId, []);

        self::assertSame([], $this->list());
        self::assertSame(0, $this->contributionCount());
        self::assertNotNull($this->meal($mealId)->getGroceryChoiceMadeAt());
    }

    public function testAnIngredientWithoutPackagingGoesOnTheListInTheRecipeUnit(): void
    {
        $mealId = $this->planMeal('curry');

        $this->choose($mealId, ['onion']);

        self::assertSame([['Oignon', 2.0, 'piece']], $this->list());
        self::assertSame([[2.0, 'piece']], $this->contributions());
    }

    public function testAForcedQuantityOverridesTheRoundedOne(): void
    {
        $mealId = $this->planMeal('curry');

        $this->bus()->dispatch(new ChooseMealGroceriesCommand(
            mealId: $mealId,
            userId: (string) $this->user()->getId(),
            chosen: [(string) $this->getFixture('rice')->getId() => 3.0],
        ));

        self::assertSame([['Riz', 3.0, 'pack']], $this->list());
    }

    /** @return iterable<string, array{float, float}> */
    public static function forcedCounts(): iterable
    {
        yield 'a fraction is rounded up' => [1.5, 2.0];
        yield 'less than one is one' => [0.2, 1.0];
    }

    #[DataProvider('forcedCounts')]
    public function testAForcedCountOfPackagingsIsAWholeNumber(float $forced, float $expected): void
    {
        $mealId = $this->planMeal('curry');

        $this->bus()->dispatch(new ChooseMealGroceriesCommand(
            mealId: $mealId,
            userId: (string) $this->user()->getId(),
            chosen: [(string) $this->getFixture('rice')->getId() => $forced],
        ));

        self::assertSame([['Riz', $expected, 'pack']], $this->list());
    }

    public function testTwoRecipeLinesOfOnePackagedProductAreRoundedUpOnce(): void
    {
        $mealId = $this->planMeal('rice_twice');
        $this->choose($mealId, ['rice']);

        self::assertSame([['Riz', 1.0, 'pack']], $this->list());
    }

    public function testALineAlreadyInTheBasketStaysAndTheChosenPackIsNotBoughtTwice(): void
    {
        $mealId = $this->planMeal('curry');
        $this->choose($mealId, ['rice']);
        $this->check('Riz');

        $this->choose($mealId, ['rice']);

        // The shopper carried the pack home: the chosen line was already in
        // packs, choosing it again asks for the same one and leaves the bought line be.
        self::assertSame([['Riz', 1.0, 'pack']], $this->list());
        self::assertTrue($this->item('Riz')->isChecked());
        self::assertSame([[300.0, 'g']], $this->contributions());
    }

    public function testAnUncheckedLineOfTheSameProductAndUnitIsJoined(): void
    {
        $this->writeLine('rice', Unit::Pack, 1.0, checked: false);
        $mealId = $this->planMeal('curry');

        $this->choose($mealId, ['rice']);

        self::assertSame([['Riz', 2.0, 'pack']], $this->list());
        self::assertSame(GroceryItemSource::Manual, $this->item('Riz')->getSource());
        self::assertSame([[300.0, 'g']], $this->contributions());
    }

    public function testACheckedLineOfTheSameProductAndUnitIsNotJoined(): void
    {
        $this->writeLine('rice', Unit::Pack, 1.0, checked: true);
        $mealId = $this->planMeal('curry');

        $this->choose($mealId, ['rice']);

        self::assertSame([['Riz', 1.0, 'pack'], ['Riz', 1.0, 'pack']], $this->list());
    }

    public function testChoosingTwiceInARowKeepsOneContributionPerLine(): void
    {
        // The gesture « revoir les ingrédients »: the same subset, then
        // another. A take-back-then-add would insert a second contribution for
        // the same pair before deleting the first — the unique index, 500.
        $mealId = $this->planMeal('curry');

        $this->choose($mealId, ['rice']);
        $this->choose($mealId, ['rice']);
        self::assertSame([['Riz', 1.0, 'pack']], $this->list());
        self::assertSame(1, $this->contributionCount());

        $this->choose($mealId, ['rice', 'onion']);
        self::assertSame([['Oignon', 2.0, 'piece'], ['Riz', 1.0, 'pack']], $this->list());
        self::assertSame(2, $this->contributionCount());

        $this->choose($mealId, ['onion']);
        self::assertSame([['Oignon', 2.0, 'piece']], $this->list());
        self::assertSame(1, $this->contributionCount());
    }

    public function testChoosingTwiceAnIngredientWithoutPackagingKeepsOneLine(): void
    {
        // Choosing it again asks for the line it already holds: it is adjusted
        // in place, never taken back and joined again.
        $mealId = $this->planMeal('curry');
        $this->choose($mealId, ['onion']);
        $before = (string) $this->item('Oignon')->getId();

        $this->choose($mealId, ['onion']);

        self::assertSame([['Oignon', 2.0, 'piece']], $this->list());
        self::assertSame($before, (string) $this->item('Oignon')->getId());
        self::assertSame(1, $this->contributionCount());
    }

    public function testAnIngredientOfNoRecipeOfTheMealIsRefusedAndNothingChanges(): void
    {
        $mealId = $this->planMeal('curry');

        try {
            $this->choose($mealId, ['rice', 'fish']);
            self::fail('an ingredient of another recipe must be refused');
        } catch (HandlerFailedException $e) {
            self::assertInstanceOf(\DomainException::class, $e->getPrevious());
        }

        self::assertSame([], $this->list());
        self::assertNull($this->meal($mealId)->getGroceryChoiceMadeAt());
    }

    public function testSyncChoiceLeavesTheCommitToItsCaller(): void
    {
        // The flush contract: the chosen lines and the meal's marker commit
        // together or not at all.
        $mealId = $this->planMeal('curry');
        $meal = $this->meal($mealId);
        $lines = $this->service()->chosenLines($meal, [(string) $this->getFixture('rice')->getId() => null]);

        $this->sync()->syncChoice($meal, $lines);
        $this->em()->clear();

        self::assertSame([], $this->list());
        self::assertNull($this->meal($mealId)->getGroceryChoiceMadeAt());
    }

    public function testTheChoicePublishesTheListAndTheMealAndIndexesThem(): void
    {
        $mealId = $this->planMeal('curry');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->choose($mealId, ['rice']);

        $this->assertMercureUpdatePublished('/api/grocery_lists/');
        $this->assertMercureUpdatePublished('/api/meals/'.$mealId);
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
        $this->assertElasticsearchIndexDispatchedFor(Meal::class, $mealId);
    }

    /** Plans tomorrow's dinner the way it is planned today, and returns its id. */
    private function planMeal(string $recipeRef): string
    {
        return $this->planMealOn($recipeRef, '+1 day');
    }

    private function planMealOn(string $recipeRef, string $when): string
    {
        $envelope = $this->bus()->dispatch(new CreateMealCommand(
            date: (new \DateTimeImmutable($when, new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: 'dinner',
            recipeIds: [(string) $this->getFixture($recipeRef)->getId()],
            userId: (string) $this->user()->getId(),
        ));

        $id = (string) $envelope->last(HandledStamp::class)->getResult()->getId();
        $this->em()->clear();

        return $id;
    }

    /** @param list<string> $ingredientRefs */
    private function choose(string $mealId, array $ingredientRefs): void
    {
        $chosen = [];
        foreach ($ingredientRefs as $ref) {
            $chosen[(string) $this->getFixture($ref)->getId()] = null;
        }

        $this->bus()->dispatch(new ChooseMealGroceriesCommand(
            mealId: $mealId,
            userId: (string) $this->user()->getId(),
            chosen: $chosen,
        ));
        $this->em()->clear();
    }

    /** @return array<string, mixed> */
    private function preview(string $mealId): array
    {
        return $this->service()->preview($this->meal($mealId));
    }

    /** @return array<string, mixed> */
    private function previewOf(string $mealId, string $name): array
    {
        foreach ($this->preview($mealId)['ingredients'] as $ingredient) {
            if ($ingredient['name'] === $name) {
                return $ingredient;
            }
        }

        throw new \LogicException("No ingredient {$name} in the preview.");
    }

    private function writeLine(string $productRef, Unit $unit, float $quantity, bool $checked): void
    {
        $list = new GroceryList();
        $list->setUser($this->user());
        $this->em()->persist($list);

        $item = new GroceryItem();
        $item->setProduct($this->em()->find(Ingredient::class, $this->getFixture($productRef)->getId()));
        $item->setUnit($unit);
        $item->setQuantity($quantity);
        $item->setChecked($checked);
        $item->setSource(GroceryItemSource::Manual);
        $item->setPosition(1);
        $list->addItem($item);
        $this->em()->persist($item);
        $this->em()->flush();
        $this->em()->clear();
    }

    private function check(string $label): void
    {
        $this->item($label)->setChecked(true);
        $this->em()->flush();
        $this->em()->clear();
    }

    /**
     * The list as stored: label, quantity and unit of each line, sorted.
     *
     * @return list<array{0: string, 1: ?float, 2: ?string}>
     */
    private function list(): array
    {
        $this->em()->clear();
        $list = $this->em()->getRepository(GroceryList::class)->findOneBy(['user' => $this->user()]);
        if (null === $list) {
            return [];
        }

        $lines = [];
        foreach ($this->em()->getRepository(GroceryItem::class)->findBy(['groceryList' => $list]) as $item) {
            $lines[] = [$item->getLabel(), $item->getQuantity(), $item->getUnit()?->value];
        }
        sort($lines);

        return $lines;
    }

    /** @return list<array{0: float, 1: ?string}> */
    private function contributions(): array
    {
        $this->em()->clear();
        $rows = array_map(
            static fn (MealGroceryContribution $c) => [$c->getQuantity(), $c->getUnit()?->value],
            $this->em()->getRepository(MealGroceryContribution::class)->findAll(),
        );
        sort($rows);

        return $rows;
    }

    private function contributionCount(): int
    {
        $this->em()->clear();

        return \count($this->em()->getRepository(MealGroceryContribution::class)->findAll());
    }

    private function item(string $label): GroceryItem
    {
        $this->em()->clear();

        foreach ($this->em()->getRepository(GroceryItem::class)->findAll() as $item) {
            if ($item->getLabel() === $label) {
                return $item;
            }
        }

        throw new \LogicException("No grocery line labelled {$label}.");
    }

    private function meal(string $id): Meal
    {
        return $this->em()->find(Meal::class, $id) ?? throw new \LogicException("No meal {$id}.");
    }

    private function user(): User
    {
        return $this->em()->getRepository(User::class)->findOneBy(['email' => 'meal-choice@example.com'])
            ?? throw new \LogicException('No user.');
    }

    private function service(): MealGroceryChoice
    {
        return self::getContainer()->get(MealGroceryChoice::class);
    }

    private function sync(): MealGrocerySync
    {
        return self::getContainer()->get(MealGrocerySync::class);
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
