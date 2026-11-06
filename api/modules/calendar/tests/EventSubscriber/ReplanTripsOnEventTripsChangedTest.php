<?php

namespace Maggie\Calendar\Tests\EventSubscriber;

use Maggie\Calendar\Event\EventTripsChangedEvent;
use Maggie\Calendar\EventSubscriber\ReplanTripsOnEventTripsChanged;
use Maggie\Calendar\Message\ReplanTripsCommand;
use Maggie\Calendar\Repository\TripRepository;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

/** MAG-656, MAG-726. « Now » is 00:30 on 3 November 2026 in Paris, still the 2nd in UTC. */
class ReplanTripsOnEventTripsChangedTest extends TestCase
{
    /** @var list<array{string, \DateTimeImmutable, \DateTimeImmutable}> */
    private array $asked = [];

    /** @return iterable<string, array{string, string}> */
    public static function changes(): iterable
    {
        yield 'created' => [EventTripsChangedEvent::CREATED, ReplanTripsCommand::EVENT_CREATED];
        yield 'updated' => [EventTripsChangedEvent::UPDATED, ReplanTripsCommand::EVENT_UPDATED];
        yield 'deleted' => [EventTripsChangedEvent::DELETED, ReplanTripsCommand::EVENT_DELETED];
    }

    /** @return object{sent: list<object>}&MessageBusInterface */
    private function bus(): MessageBusInterface
    {
        return new class implements MessageBusInterface {
            /** @var list<object> */
            public array $sent = [];

            public function dispatch(object $message, array $stamps = []): Envelope
            {
                $this->sent[] = $message;

                return new Envelope($message);
            }
        };
    }

    private function subscriber(MessageBusInterface $bus, bool $hasTrip): ReplanTripsOnEventTripsChanged
    {
        $trips = $this->createStub(TripRepository::class);
        $trips->method('hasLeavingBetween')->willReturnCallback(function (string $userId, \DateTimeImmutable $from, \DateTimeImmutable $to) use ($hasTrip): bool {
            $this->asked[] = [$userId, $from, $to];

            return $hasTrip;
        });

        return new ReplanTripsOnEventTripsChanged($bus, $trips, new MockClock('2026-11-03 00:30:00', 'Europe/Paris'));
    }

    #[DataProvider('changes')]
    public function testTheFactBecomesAReplanCommandForTheSameUserWithItsReasonWhenATripExists(string $change, string $reason): void
    {
        $bus = $this->bus();

        $this->subscriber($bus, true)(new EventTripsChangedEvent('01J0EVENT', '01J0USER', $change));

        self::assertEquals([new ReplanTripsCommand('01J0USER', $reason)], $bus->sent);
    }

    public function testTheTripsLookedUpAreThoseOfTheParisDay(): void
    {
        $this->subscriber($this->bus(), true)(new EventTripsChangedEvent('01J0EVENT', '01J0USER', EventTripsChangedEvent::DELETED));

        self::assertCount(1, $this->asked);
        self::assertSame('2026-11-03 00:00', $this->asked[0][1]->format('Y-m-d H:i'));
        self::assertSame('2026-11-04 00:00', $this->asked[0][2]->format('Y-m-d H:i'));
    }

    #[DataProvider('changes')]
    public function testWithoutATripTodayOnlyACreationReplans(string $change, string $reason): void
    {
        $bus = $this->bus();

        $this->subscriber($bus, false)(new EventTripsChangedEvent('01J0EVENT', '01J0USER', $change));

        self::assertEquals(EventTripsChangedEvent::CREATED === $change ? [new ReplanTripsCommand('01J0USER', $reason)] : [], $bus->sent);
    }

    public function testAnUpdateThatAddsALocationReplansEvenWithoutATrip(): void
    {
        $bus = $this->bus();

        $this->subscriber($bus, false)(new EventTripsChangedEvent('01J0EVENT', '01J0USER', EventTripsChangedEvent::UPDATED, true));

        self::assertEquals([new ReplanTripsCommand('01J0USER', ReplanTripsCommand::EVENT_UPDATED)], $bus->sent);
        self::assertSame([], $this->asked);
    }
}
