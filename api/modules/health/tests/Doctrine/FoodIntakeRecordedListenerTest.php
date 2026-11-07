<?php

namespace Maggie\Health\Tests\Doctrine;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Events;
use Maggie\Health\Doctrine\FoodIntakeRecordedListener;
use Maggie\Health\Entity\FoodIntake;
use Maggie\Health\Event\FoodIntakeRecorded;
use Maggie\Health\Message\CreateFoodIntakeCommand;
use Maggie\Health\Message\UpdateFoodIntakeCommand;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** FoodIntakeRecorded goes out once per inserted intake, after the flush, whichever door saved it (MAG-748). */
class FoodIntakeRecordedListenerTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    /** @var list<object> */
    private array $events = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/food_intake.yaml');

        // The real listener keeps running; this one only records what it would send.
        $this->em()->getEventManager()->addEventListener(
            [Events::preFlush, Events::onFlush, Events::postFlush],
            new FoodIntakeRecordedListener($this->spyBus(), new NullLogger()),
        );
        $this->events = [];
    }

    /** @internal */
    public function record(object $event): void
    {
        $this->events[] = $event;
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function bus(): MessageBusInterface
    {
        $bus = self::getContainer()->get('messenger.bus.default');
        \assert($bus instanceof MessageBusInterface);

        return $bus;
    }

    private function spyBus(): MessageBusInterface
    {
        return new class($this) implements MessageBusInterface {
            public function __construct(private readonly FoodIntakeRecordedListenerTest $test)
            {
            }

            public function dispatch(object $message, array $stamps = []): Envelope
            {
                $this->test->record($message);

                return new Envelope($message);
            }
        };
    }

    public function testARecordedIntakeRaisesTheEventWithItsDay(): void
    {
        $user = (string) $this->getFixture('test_user')->getId();

        $intake = $this->bus()->dispatch(new CreateFoodIntakeCommand(
            userId: $user,
            eatenAt: '2026-11-05T12:00:00+01:00',
            label: 'Salade César',
            kcal: 450,
        ))->last(HandledStamp::class)?->getResult();
        \assert($intake instanceof FoodIntake);

        self::assertCount(1, $this->events);
        $event = $this->events[0];
        self::assertInstanceOf(FoodIntakeRecorded::class, $event);
        self::assertSame((string) $intake->getId(), $event->foodIntakeId);
        self::assertSame($user, $event->userId);
        self::assertSame('2026-11-05', $event->date);
    }

    public function testAnUpdateRaisesNothing(): void
    {
        $this->bus()->dispatch(new UpdateFoodIntakeCommand(
            userId: (string) $this->getFixture('test_user')->getId(),
            foodIntakeId: (string) $this->getFixture('apple_4')->getId(),
            kcal: 90,
        ));

        self::assertSame([], $this->events);
    }

    public function testARefusedIntakeRaisesNothing(): void
    {
        try {
            $this->bus()->dispatch(new CreateFoodIntakeCommand(userId: (string) $this->getFixture('test_user')->getId(), label: 'Pomme', kcal: 52));
            self::fail('An intake without a moment must be refused.');
        } catch (\Symfony\Component\Messenger\Exception\HandlerFailedException) {
        }

        self::assertSame([], $this->events);
    }
}
