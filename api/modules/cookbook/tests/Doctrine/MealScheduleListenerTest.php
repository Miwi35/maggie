<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Doctrine;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Events;
use Maggie\Cookbook\Doctrine\MealScheduleListener;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Event\MealRemoved;
use Maggie\Cookbook\Event\MealRescheduled;
use Maggie\Cookbook\Message\CreateMealCommand;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

class MealScheduleListenerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    /** @var list<object> what the spy listener sent */
    private array $dispatched = [];

    /** @var list<bool> */
    private array $transactionOpenWhenSent = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../MessageHandler/fixtures/MealGrocerySyncTest.yaml');
        $this->loginFixtureUser();
        $this->em()->clear();
        $this->dispatched = [];

        // The real listener would take the contributions out of the spy's reach: this one stands in for it.
        $manager = $this->em()->getEventManager();
        foreach ([Events::preFlush, Events::onFlush, Events::postFlush] as $name) {
            foreach ($manager->getListeners($name) as $listener) {
                if ($listener instanceof MealScheduleListener) {
                    $manager->removeEventListener([$name], $listener);
                }
            }
        }
        $manager->addEventListener(
            [Events::preFlush, Events::onFlush, Events::postFlush],
            new MealScheduleListener($this->spyBus(), new NullLogger()),
        );
    }

    public function testDeletingAMealSendsTheEventWithIdsAndItsContributionsOnceItIsGone(): void
    {
        $mealId = $this->planMeal('pasta', '+1 day');
        $meal = $this->em()->find(Meal::class, $mealId);
        $userId = (string) $meal->getAgenda()->getUser()->getId();
        $this->dispatched = [];

        $this->em()->remove($meal);
        $this->em()->flush();

        self::assertCount(1, $this->dispatched);
        $event = $this->dispatched[0];
        self::assertInstanceOf(MealRemoved::class, $event);
        self::assertSame($mealId, $event->mealId);
        self::assertSame($userId, $event->userId);
        self::assertEqualsCanonicalizing([400.0, 4.0], array_column($event->contributions, 'quantity'));
        self::assertSame([false], $this->transactionOpenWhenSent, 'the event goes out after the flush is committed');
        self::assertNull($this->em()->find(Meal::class, $mealId));
    }

    public function testMovingAMealSendsTheEventAndRenamingItDoesNot(): void
    {
        $mealId = $this->planMeal('fish_dish', '+10 days');
        $this->dispatched = [];

        $meal = $this->em()->find(Meal::class, $mealId);
        $meal->setSummary('Poisson du jeudi');
        $this->em()->flush();
        self::assertSame([], $this->dispatched);

        $meal->setStartAt($meal->getStartAt()->modify('+3 days'));
        $this->em()->flush();

        self::assertEquals([new MealRescheduled($mealId)], $this->dispatched);
    }

    public function testWritingTheSameInstantAgainSendsNothing(): void
    {
        $mealId = $this->planMeal('fish_dish', '+10 days');
        $this->dispatched = [];

        $meal = $this->em()->find(Meal::class, $mealId);
        $meal->setStartAt(new \DateTimeImmutable($meal->getStartAt()->format('c')));
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }

    public function testPlanningAMealSendsNothing(): void
    {
        $this->planMeal('pasta', '+1 day');

        self::assertSame([], $this->dispatched);
    }

    public function testAFailingEventDoesNotUndoTheSave(): void
    {
        $mealId = $this->planMeal('fish_dish', '+10 days');
        $this->em()->getEventManager()->addEventListener(
            [Events::postFlush],
            new MealScheduleListener(new class implements MessageBusInterface {
                public function dispatch(object $message, array $stamps = []): Envelope
                {
                    throw new \RuntimeException('sync failed');
                }
            }, new NullLogger()),
        );

        $meal = $this->em()->find(Meal::class, $mealId);
        $moved = $meal->getStartAt()->modify('+3 days');
        $meal->setStartAt($moved);
        $this->em()->flush();

        $this->em()->clear();
        self::assertEquals($moved, $this->em()->find(Meal::class, $mealId)->getStartAt());
        self::assertNotEmpty($this->dispatched, 'the other listener still got its event');
    }

    public function testChangesOfAFlushThatFailedAreNotSentWithTheNextFlush(): void
    {
        $mealId = $this->planMeal('fish_dish', '+10 days');
        $this->dispatched = [];

        $meal = $this->em()->find(Meal::class, $mealId);
        $meal->setStartAt($meal->getStartAt()->modify('+3 days'));
        $manager = $this->em()->getEventManager();
        $boom = new class {
            public function onFlush(): void
            {
                throw new \RuntimeException('flush failed');
            }
        };
        $manager->addEventListener([Events::onFlush], $boom);
        try {
            $this->em()->flush();
            self::fail('The flush should have failed.');
        } catch (\RuntimeException) {
        }
        $manager->removeEventListener([Events::onFlush], $boom);
        $this->dispatched = [];

        $this->em()->clear();
        $this->em()->find(Meal::class, $mealId)->setSummary('Poisson');
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }

    /** @internal */
    public function record(object $event): void
    {
        $this->transactionOpenWhenSent[] = $this->em()->getConnection()->isTransactionActive();
        $this->dispatched[] = $event;
    }

    private function planMeal(string $recipeRef, string $when): string
    {
        $envelope = self::getContainer()->get(MessageBusInterface::class)->dispatch(new CreateMealCommand(
            date: (new \DateTimeImmutable($when, new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: 'dinner',
            recipeIds: [(string) $this->getFixture($recipeRef)->getId()],
            userId: (string) $this->getFixture('test_user')->getId(),
        ));

        return (string) $envelope->last(HandledStamp::class)->getResult()->getId();
    }

    private function spyBus(): MessageBusInterface
    {
        return new class($this) implements MessageBusInterface {
            public function __construct(private readonly MealScheduleListenerTest $test)
            {
            }

            public function dispatch(object $message, array $stamps = []): Envelope
            {
                $this->test->record($message);

                return new Envelope($message);
            }
        };
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
