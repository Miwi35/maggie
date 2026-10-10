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
use Maggie\Cookbook\Message\RevokeMealGroceriesCommand;
use Maggie\Cookbook\Message\SyncMealGroceriesCommand;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

class RevokeMealGroceriesHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('MealGrocerySyncTest.yaml');
        $this->loginFixtureUser();
        $this->em()->clear();
    }

    public function testTheShareOfAMealGoneFromTheAgendaLeavesTheListAndIsPublishedAndReindexed(): void
    {
        $mealId = $this->planMeal('pasta');
        $contributions = $this->snapshot();
        // What the database cascade does when the meal row goes.
        $this->em()->getConnection()->executeStatement('DELETE FROM meal_grocery_contribution');
        $this->em()->clear();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->bus()->dispatch(new RevokeMealGroceriesCommand($mealId, (string) $this->getFixture('test_user')->getId(), $contributions));

        $this->em()->clear();
        self::assertCount(0, $this->em()->getRepository(GroceryItem::class)->findAll());
        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testAMealThatHadPutNothingOnTheListPublishesNothing(): void
    {
        $this->resetMercure();

        $this->bus()->dispatch(new RevokeMealGroceriesCommand('01JZZZZZZZZZZZZZZZZZZZZZZZ', (string) $this->getFixture('test_user')->getId(), []));

        $this->assertMercureUpdateCount(0);
    }

    public function testALineDeletedInTheMeantimeIsSkipped(): void
    {
        $this->planMeal('pasta');
        $contributions = $this->snapshot();
        $this->em()->getConnection()->executeStatement('DELETE FROM meal_grocery_contribution');
        $this->em()->getConnection()->executeStatement('DELETE FROM grocery_item');
        $this->em()->clear();

        $this->bus()->dispatch(new RevokeMealGroceriesCommand('01JZZZZZZZZZZZZZZZZZZZZZZZ', (string) $this->getFixture('test_user')->getId(), $contributions));

        $this->em()->clear();
        self::assertCount(0, $this->em()->getRepository(GroceryItem::class)->findAll());
    }

    public function testSyncingAMealThatIsGoneChangesNothing(): void
    {
        $this->planMeal('pasta');
        $this->resetMercure();

        $this->bus()->dispatch(new SyncMealGroceriesCommand('01JZZZZZZZZZZZZZZZZZZZZZZZ'));

        $this->assertMercureUpdateCount(0);
        $this->em()->clear();
        self::assertCount(2, $this->em()->getRepository(MealGroceryContribution::class)->findAll());
    }

    public function testSyncingAStoredMealPublishesTheList(): void
    {
        $mealId = $this->planMeal('pasta');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->bus()->dispatch(new SyncMealGroceriesCommand($mealId));

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    /** @return list<array{groceryItemId: string, quantity: float}> */
    private function snapshot(): array
    {
        $this->em()->clear();
        $snapshot = [];
        foreach ($this->em()->getRepository(MealGroceryContribution::class)->findAll() as $contribution) {
            $snapshot[] = ['groceryItemId' => (string) $contribution->getGroceryItem()->getId(), 'quantity' => $contribution->getQuantity()];
        }

        return $snapshot;
    }

    private function planMeal(string $recipeRef): string
    {
        $envelope = $this->bus()->dispatch(new CreateMealCommand(
            date: (new \DateTimeImmutable('+1 day', new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: 'dinner',
            recipeIds: [(string) $this->getFixture($recipeRef)->getId()],
            userId: (string) $this->getFixture('test_user')->getId(),
        ));

        return (string) $envelope->last(HandledStamp::class)->getResult()->getId();
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
