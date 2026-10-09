<?php

namespace Maggie\Cookbook\Tests\Projection;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Message\DeleteEventCommand;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * A meal drags a grocery list, an agenda and its own parent topic along: all
 * of it reaches the screens and the index once, whoever triggered it (MAG-371).
 * No user is logged in here — the owner is read from the entities.
 */
class MealProjectionTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../MessageHandler/fixtures/MealGrocerySyncTest.yaml');
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function planMeal(): string
    {
        $envelope = self::getContainer()->get(MessageBusInterface::class)->dispatch(new CreateMealCommand(
            date: (new \DateTimeImmutable('+1 day', new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: 'dinner',
            recipeIds: [(string) $this->getFixture('pasta')->getId()],
            userId: (string) $this->getFixture('test_user')->getId(),
        ));

        return (string) $envelope->last(HandledStamp::class)->getResult()->getId();
    }

    private function listId(): string
    {
        $list = self::getContainer()->get('doctrine.orm.entity_manager')
            ->getRepository(GroceryList::class)->findOneBy(['user' => $this->getFixture('test_user')->getId()]);
        self::assertNotNull($list);

        return (string) $list->getId();
    }

    public function testPlanningAMealPublishesTheMealOnBothTopicsAndTheListOnce(): void
    {
        $mealId = $this->planMeal();

        $this->assertMercurePublishedOnce('/api/meals/'.$mealId);
        $this->assertMercurePublishedOnce('/api/events/'.$mealId);
        $this->assertMercurePublishedOnce('/api/grocery_lists/'.$this->listId());
        self::assertSame([$mealId], $this->reindexedIdsOf(Meal::class), 'The meal is reindexed once');
        self::assertSame([$this->listId()], $this->reindexedIdsOf(GroceryList::class), 'The list is reindexed once');
    }

    public function testDeletingAMealThroughTheEventCommandPublishesItsDeletionOnBothTopics(): void
    {
        $mealId = $this->planMeal();
        $this->resetMercure();
        $this->resetAsyncTransport();

        self::getContainer()->get(MessageBusInterface::class)->dispatch(new DeleteEventCommand(eventId: $mealId));

        $this->assertMercureDeletePublished('/api/events/'.$mealId);
        $this->assertMercureDeletePublished('/api/meals/'.$mealId);
        $this->assertElasticsearchDeleteDispatched('meals');
        $this->assertElasticsearchDeleteDispatched('events');
    }
}
