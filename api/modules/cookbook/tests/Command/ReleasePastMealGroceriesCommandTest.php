<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\MealGroceryContribution;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Tester\CommandTester;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Uid\Ulid;

/**
 * `maggie:cookbook:release-past-meal-groceries` — a meal whose day has gone
 * has nothing left to buy: its unbought share leaves the list (MAG-400).
 */
final class ReleasePastMealGroceriesCommandTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use SecurityTokenTrait;

    private CommandTester $tester;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../MessageHandler/fixtures/MealGrocerySyncTest.yaml');
        $this->loginFixtureUser();
        $this->em()->clear();

        $this->tester = new CommandTester((new Application(self::$kernel))->find('maggie:cookbook:release-past-meal-groceries'));
    }

    public function testAYesterdaysMealLeavesTheListAndTheLineGoesWhenItWasTheOnlySource(): void
    {
        $this->planMealThatThenPasses('pasta', '-1 day');
        $this->planMeal('gratin', '+1 day');

        $this->tester->execute([]);

        $this->tester->assertCommandIsSuccessful();
        self::assertSame(['Parmesan' => 80.0, 'Tomate' => 2.0], $this->list());
        self::assertSame(2, $this->contributionCount(), 'only the meal of tomorrow still holds lines');
    }

    public function testOnlyTheShareOfThePastMealLeavesASharedLine(): void
    {
        $this->planMealThatThenPasses('pasta', '-1 day');
        $this->planMeal('gratin', '+1 day');
        self::assertSame(6.0, $this->list()['Tomate']);

        $this->tester->execute([]);

        self::assertSame(2.0, $this->list()['Tomate']);
    }

    public function testALineAlreadyInTheBasketIsLeftIntact(): void
    {
        $this->planMealThatThenPasses('pasta', '-1 day');
        $this->check('Pâtes');
        $before = $this->contributionCount();

        $this->tester->execute([]);

        self::assertSame(['Pâtes' => 400.0], $this->list());
        self::assertSame(1, $this->contributionCount(), 'the checked line keeps its link, the tomato one goes');
        self::assertSame(2, $before);
    }

    public function testTodaysMealIsStillToBeEaten(): void
    {
        $this->planMeal('pasta', 'today');

        $this->tester->execute([]);

        self::assertSame(['Pâtes' => 400.0, 'Tomate' => 4.0], $this->list());
    }

    public function testTwoPastMealsSharingALineBothLeaveIt(): void
    {
        $this->planMealThatThenPasses('pasta', '-2 days');
        $this->planMealThatThenPasses('gratin', '-1 day');

        $this->tester->execute([]);

        self::assertSame([], $this->list());
        self::assertSame(0, $this->contributionCount());
    }

    public function testThePastMealItselfStaysInTheAgenda(): void
    {
        $mealId = $this->planMealThatThenPasses('pasta', '-1 day');

        $this->tester->execute([]);

        $this->em()->clear();
        self::assertNotNull($this->em()->getRepository(Meal::class)->find($mealId));
    }

    public function testRunningItAgainTheSameDayChangesNothingAndPublishesNothing(): void
    {
        $this->planMealThatThenPasses('pasta', '-1 day');
        $this->planMeal('gratin', '+1 day');
        $this->tester->execute([]);
        $after = $this->list();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->tester->execute([]);

        self::assertSame($after, $this->list());
        $this->assertMercureUpdateCount(0);
        self::assertStringContainsString('No past meal holds shopping.', $this->tester->getDisplay());
    }

    public function testTheListIsPublishedAndReindexed(): void
    {
        $this->planMealThatThenPasses('pasta', '-1 day');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->tester->execute([]);

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    /** Planned ahead like any meal, then its day goes by. */
    private function planMealThatThenPasses(string $recipeRef, string $when): string
    {
        $mealId = $this->planMeal($recipeRef, '+1 day');
        $this->em()->getConnection()->executeStatement(
            'UPDATE meal SET date = ? WHERE id = ?',
            [$this->day($when), Ulid::fromString($mealId)->toRfc4122()],
        );
        $this->em()->clear();

        return $mealId;
    }

    private function planMeal(string $recipeRef, string $when): string
    {
        $envelope = $this->bus()->dispatch(new CreateMealCommand(
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

    private function check(string $label): void
    {
        $this->em()->clear();

        foreach ($this->em()->getRepository(GroceryItem::class)->findAll() as $item) {
            if ($item->getLabel() === $label) {
                $item->setChecked(true);
            }
        }

        $this->em()->flush();
    }

    /** @return array<string, float|null> label => quantity */
    private function list(): array
    {
        $this->em()->clear();
        $lines = [];

        foreach ($this->em()->getRepository(GroceryItem::class)->findAll() as $item) {
            $lines[$item->getLabel()] = $item->getQuantity();
        }
        ksort($lines);

        return $lines;
    }

    private function contributionCount(): int
    {
        $this->em()->clear();

        return \count($this->em()->getRepository(MealGroceryContribution::class)->findAll());
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
