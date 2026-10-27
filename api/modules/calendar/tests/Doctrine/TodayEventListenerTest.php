<?php

namespace Maggie\Calendar\Tests\Doctrine;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Events;
use Maggie\Calendar\Doctrine\TodayEventListener;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Event\EventTripsChangedEvent;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

/** MAG-656. The clock reads 10:00 on Tuesday 3 November 2026 in Paris, the day of the fixtures' events. */
class TodayEventListenerTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    /** @var list<object> */
    private array $dispatched = [];
    /** @var list<bool> */
    private array $transactionOpenWhenSent = [];

    protected function setUp(): void
    {
        self::bootKernel();
        // Loaded on a day no fixture event falls on, so the load itself triggers nothing.
        Clock::set(new MockClock('2020-01-01 12:00:00', 'Europe/Paris'));
        $this->loadFixtures(\dirname(__DIR__).'/Mcp/fixtures/EstimateTripToolTest.yaml');
        Clock::set(new MockClock('2026-11-03 10:00:00', 'Europe/Paris'));
        $this->dispatched = [];
        $this->transactionOpenWhenSent = [];

        // The real listener steps aside: its chain files a proaction, a flush inside the flush that would make
        // this one forget what it noted. The chain has its own test (ReplanTripsTest).
        $manager = $this->em()->getEventManager();
        foreach ($manager->getAllListeners() as $event => $listeners) {
            foreach ($listeners as $listener) {
                if ($listener instanceof TodayEventListener) {
                    $manager->removeEventListener([$event], $listener);
                }
            }
        }
        $manager->addEventListener(
            [Events::preFlush, Events::onFlush, Events::postFlush],
            new TodayEventListener($this->spyBus(), Clock::get(), new NullLogger()),
        );
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function event(string $fixture): Event
    {
        return $this->em()->find(Event::class, $this->getFixture($fixture)->getId());
    }

    private function at(string $parisTime): \DateTimeImmutable
    {
        return new \DateTimeImmutable($parisTime, new \DateTimeZone('Europe/Paris'));
    }

    private function spyBus(): MessageBusInterface
    {
        return new class($this) implements MessageBusInterface {
            public function __construct(private readonly TodayEventListenerTest $test)
            {
            }

            public function dispatch(object $message, array $stamps = []): Envelope
            {
                $this->test->record($message);

                return new Envelope($message);
            }
        };
    }

    /** @internal */
    public function record(object $event): void
    {
        $this->dispatched[] = $event;
        $this->transactionOpenWhenSent[] = $this->em()->getConnection()->isTransactionActive();
    }

    private function userId(): string
    {
        return (string) $this->getFixture('test_user')->getId();
    }

    public function testMovingATodayEventSendsTheEventOnceItIsStored(): void
    {
        $dentist = $this->event('dentist');
        $dentist->scheduleTimed($this->at('2026-11-03 15:00:00'), $this->at('2026-11-03 16:00:00'));
        $this->em()->flush();

        self::assertEquals([new EventTripsChangedEvent((string) $dentist->getId(), $this->userId(), EventTripsChangedEvent::UPDATED)], $this->dispatched);
        self::assertSame([false], $this->transactionOpenWhenSent, 'the event goes out after the flush is committed');
    }

    public function testRelocatingATodayEventSendsTheEvent(): void
    {
        $this->event('dentist')->setLocation('12 rue du Nouveau Cabinet Fictif');
        $this->em()->flush();

        self::assertCount(1, $this->dispatched);
    }

    public function testCreatingATodayEventWithALocationSendsTheEvent(): void
    {
        $event = (new Event())->setSummary('Coiffeur')->setLocation('3 rue des Ciseaux Fictifs');
        $event->setTimeZone('Europe/Paris');
        $event->scheduleTimed($this->at('2026-11-03 17:00:00'), $this->at('2026-11-03 18:00:00'));
        $this->em()->find(Agenda::class, $this->getFixture('own_agenda')->getId())->addEvent($event);
        $this->em()->persist($event);
        $this->em()->flush();

        self::assertEquals([new EventTripsChangedEvent((string) $event->getId(), $this->userId(), EventTripsChangedEvent::CREATED)], $this->dispatched);
    }

    public function testRemovingATodayEventSendsTheEventWithItsOwner(): void
    {
        $dentist = $this->event('dentist');
        $id = (string) $dentist->getId();
        $this->em()->remove($dentist);
        $this->em()->flush();

        self::assertEquals([new EventTripsChangedEvent($id, $this->userId(), EventTripsChangedEvent::DELETED)], $this->dispatched);
    }

    public function testChangingOnlyTheTitleSendsNothing(): void
    {
        $this->event('dentist')->setSummary('Dentiste d\'Ael');
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }

    public function testSavingTheSameStartAgainSendsNothing(): void
    {
        $dentist = $this->event('dentist');
        $dentist->setStartAt($this->at('2026-11-03 14:00:00'));
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }

    public function testAnEventOfAnotherDaySendsNothing(): void
    {
        $dentist = $this->event('dentist');
        $dentist->scheduleTimed($this->at('2026-11-10 14:00:00'), $this->at('2026-11-10 15:00:00'));
        $this->em()->flush();
        // It left today: that one counts. The next change is on another day only.
        $this->dispatched = [];

        $dentist->setLocation('12 rue du Nouveau Cabinet Fictif');
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }

    public function testChangesOfAFlushThatFailedAreNotSentWithTheNextFlush(): void
    {
        $this->event('dentist')->setLocation('12 rue du Nouveau Cabinet Fictif');
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
        $this->event('dentist')->setSummary('Dentiste d\'Ael');
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }

    public function testAFailingEventDoesNotUndoTheSave(): void
    {
        $this->em()->getEventManager()->addEventListener(
            [Events::preFlush, Events::onFlush, Events::postFlush],
            new TodayEventListener(new class implements MessageBusInterface {
                public function dispatch(object $message, array $stamps = []): Envelope
                {
                    throw new \RuntimeException('replan failed');
                }
            }, Clock::get(), new NullLogger()),
        );

        $this->event('dentist')->setLocation('12 rue du Nouveau Cabinet Fictif');
        $this->em()->flush();

        $this->em()->clear();
        self::assertSame('12 rue du Nouveau Cabinet Fictif', $this->event('dentist')->getLocation());
        self::assertCount(1, $this->dispatched, 'the other listener still got its event');
    }
}
