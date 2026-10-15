<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Cookbook\Message\UpdateMealCommand;
use Maggie\Cookbook\Message\UpdateRecipeCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** A meal is titled after its recipes, and the title follows them (MAG-378). */
class MealTitleFollowsRecipeTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('MealTitleFollowsRecipeTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function bus(): MessageBusInterface
    {
        return self::getContainer()->get(MessageBusInterface::class);
    }

    private function createMeal(string ...$recipes): Meal
    {
        $envelope = $this->bus()->dispatch(new CreateMealCommand(
            date: '2026-10-07',
            slot: 'lunch',
            recipeIds: array_map(fn (string $key) => (string) $this->getFixture($key)->getId(), $recipes),
            userId: (string) $this->getFixture('test_user')->getId(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }

    private function reload(Meal $meal): Meal
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Meal::class)->find($meal->getId());
    }

    public function testAMealIsTitledAfterItsRecipeWithoutTheSlot(): void
    {
        $meal = $this->createMeal('pasta');

        self::assertSame('Pâtes', $this->reload($meal)->getSummary());
    }

    public function testTwoRecipesAreJoinedInTheOrderChosen(): void
    {
        $meal = $this->createMeal('pasta', 'salad');

        self::assertSame('Pâtes, Salade verte', $this->reload($meal)->getSummary());
    }

    public function testChangingTheRecipesRetitlesTheMeal(): void
    {
        $meal = $this->createMeal('pasta');

        $this->bus()->dispatch(new UpdateMealCommand(
            mealId: (string) $meal->getId(),
            recipeIds: [(string) $this->getFixture('salad')->getId()],
        ));

        self::assertSame('Salade verte', $this->reload($meal)->getSummary());
    }

    public function testRenamingARecipeRetitlesItsMeals(): void
    {
        $meal = $this->createMeal('pasta', 'salad');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->bus()->dispatch(new UpdateRecipeCommand(
            recipeId: (string) $this->getFixture('pasta')->getId(),
            name: 'Pâtes au pesto',
        ));

        self::assertSame('Pâtes au pesto, Salade verte', $this->reload($meal)->getSummary());
        $this->assertMercureUpdatePublished('/meals/'.$meal->getId());
        $this->assertElasticsearchIndexDispatched(Meal::class);
    }

    public function testUpdatingARecipeWithoutRenamingItLeavesTheTitleAlone(): void
    {
        $meal = $this->createMeal('pasta');

        $this->bus()->dispatch(new UpdateRecipeCommand(
            recipeId: (string) $this->getFixture('pasta')->getId(),
            servings: 6,
        ));

        self::assertSame('Pâtes', $this->reload($meal)->getSummary());
    }

    public function testARenamedRecipeLeavesAMealTheOwnerTitledAlone(): void
    {
        $meal = $this->createMeal('pasta');
        $this->bus()->dispatch(new UpdateMealCommand(
            mealId: (string) $meal->getId(),
            customTitle: 'Repas des cousins',
        ));

        $this->bus()->dispatch(new UpdateRecipeCommand(
            recipeId: (string) $this->getFixture('pasta')->getId(),
            name: 'Pâtes au pesto',
        ));

        self::assertSame('Repas des cousins', $this->reload($meal)->getSummary());
    }

    public function testAnUpdateThatSaysNothingOfTheTitleKeepsTheOwners(): void
    {
        $meal = $this->createMeal('pasta');
        $this->bus()->dispatch(new UpdateMealCommand(
            mealId: (string) $meal->getId(),
            customTitle: 'Repas des cousins',
        ));

        $this->bus()->dispatch(new UpdateMealCommand(
            mealId: (string) $meal->getId(),
            slot: 'dinner',
        ));

        self::assertSame('Repas des cousins', $this->reload($meal)->getSummary());
    }
}
