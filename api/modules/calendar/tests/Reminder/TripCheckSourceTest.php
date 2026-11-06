<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Reminder;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Enum\TripDecider;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Message\ReplanTripsCommand;
use Maggie\Calendar\Reminder\TripCheckSource;
use Maggie\Calendar\Routing\RouteLeg;
use Maggie\Calendar\Service\TripEstimate;
use Maggie\Calendar\Service\TripOrigin;
use Maggie\Calendar\Tests\Support\FakeRoutingTrait;
use Maggie\Core\Message\ReportUserLocationCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * The checks before a departure (MAG-730): the cron recomputes a trip at `departAt` − 30 and − 10
 * minutes, without the model. The dentist is at 14:00 Paris on 3 November 2026; the routing says
 * every trip lasts `$routeSeconds`. The trips below are seeded to leave at 13:00 Paris.
 */
final class TripCheckSourceTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FakeRoutingTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2020-01-01 12:00:00', 'Europe/Paris'));
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures(\dirname(__DIR__).'/Mcp/fixtures/EstimateTripToolTest.yaml');
        Clock::set(new MockClock('2026-11-03 12:00:00', 'Europe/Paris'));
        $this->fakeRouting();
        $this->routeSeconds = 600;
        $this->routingDown = false;
        self::getContainer()->get('messenger.bus.default')->dispatch(new ReportUserLocationCommand($this->id('test_user'), 48.11, -1.68, 10.0, $this->at('2026-11-03 12:00:00')));
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testNothingIsCheckedBeforeThirtyMinutesToDeparture(): void
    {
        $this->seed('dentist', '2026-11-03 13:00:00');

        $report = $this->source()->remind($this->at('2026-11-03 12:29:59'));

        self::assertSame(0, $report->created);
        self::assertNull($this->trip('dentist')->getLastCheckedAt());
    }

    public function testATripThatHoldsIsCheckedOnceAtEachCheckpointWithoutProaction(): void
    {
        $this->seed('dentist', '2026-11-03 13:45:00');

        $first = $this->source()->remind($this->at('2026-11-03 13:15:00'));
        $again = $this->source()->remind($this->at('2026-11-03 13:16:00'));
        $second = $this->source()->remind($this->at('2026-11-03 13:35:00'));
        $secondAgain = $this->source()->remind($this->at('2026-11-03 13:36:00'));

        self::assertSame([1, 0, 1, 0], [$first->created, $again->created, $second->created, $secondAgain->created]);
        self::assertSame([], $this->replans());
        self::assertEquals($this->at('2026-11-03 13:35:00'), $this->trip('dentist')->getLastCheckedAt());
        self::assertEquals($this->at('2026-11-03 13:45:00'), $this->trip('dentist')->getDepartAt());
    }

    public function testATripSeededInsideTheWindowIsCheckedOnceNotOncePerPassedCheckpoint(): void
    {
        $this->seed('dentist', '2026-11-03 13:00:00');

        $first = $this->source()->remind($this->at('2026-11-03 12:55:00'));
        $second = $this->source()->remind($this->at('2026-11-03 12:56:00'));

        self::assertSame([1, 0], [$first->created, $second->created]);
    }

    public function testADepartAtMovedByMoreThanTwoMinutesIsKept(): void
    {
        $this->seed('dentist', '2026-11-03 13:00:00', durationMinutes: 10);
        $before = $this->trip('dentist')->getDepartAt();

        $this->source()->remind($this->at('2026-11-03 12:30:00'));

        $after = $this->trip('dentist')->getDepartAt();
        self::assertNotEquals($before, $after);
        self::assertSame([], $this->replans());
    }

    public function testATripThatCannotBeComputedWakesMaggieWithAReplanNamingTheEvent(): void
    {
        $this->seed('dentist', '2026-11-03 13:00:00');
        $this->routingDown = true;

        $this->source()->remind($this->at('2026-11-03 12:30:00'));

        $replans = $this->replans();
        self::assertCount(1, $replans);
        self::assertSame(ReplanTripsCommand::SUMMARY, $replans[0]->getSummary());
        self::assertStringContainsString('Dentiste', $replans[0]->getPrompt());
    }

    public function testATripWhoseEventHasStartedOrWhoseDepartureHasPassedIsNotChecked(): void
    {
        $this->seed('dentist', '2026-11-03 13:00:00');

        self::assertSame(0, $this->source()->remind($this->at('2026-11-03 13:00:00'))->created);
        self::assertSame(0, $this->source()->remind($this->at('2026-11-03 14:00:00'))->created);
    }

    public function testACancelledEventIsNotChecked(): void
    {
        $this->seed('dentist', '2026-11-03 13:00:00');
        $event = $this->em()->find(Event::class, $this->id('dentist'));
        $event->setStatus(EventStatus::Cancelled);
        $this->em()->flush();

        self::assertSame(0, $this->source()->remind($this->at('2026-11-03 12:30:00'))->created);
    }

    private function source(): TripCheckSource
    {
        return self::getContainer()->get(TripCheckSource::class);
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    private function at(string $parisTime): \DateTimeImmutable
    {
        return new \DateTimeImmutable($parisTime, new \DateTimeZone('Europe/Paris'));
    }

    private function trip(string $eventRef): Trip
    {
        $this->em()->clear();
        $trip = $this->em()->getRepository(Trip::class)->findOneBy(['event' => $this->id($eventRef)]);
        self::assertInstanceOf(Trip::class, $trip);

        return $trip;
    }

    /** @return list<Proaction> */
    private function replans(): array
    {
        $this->em()->clear();

        return $this->em()->getRepository(Proaction::class)->findBy(['summary' => ReplanTripsCommand::SUMMARY]);
    }

    private function seed(string $eventRef, string $departAt, int $durationMinutes = 10): void
    {
        $event = $this->em()->find(Event::class, $this->id($eventRef));
        $trip = (new Trip($event))->apply(new TripEstimate(
            mode: TripMode::Transit,
            origin: new TripOrigin(TripOrigin::POSITION, null, 'ma position', 48.11, -1.68),
            steps: [new RouteLeg(TripMode::Transit, $durationMinutes * 60)],
            durationMinutes: $durationMinutes,
            departAt: $this->at($departAt),
            navigationUrl: 'https://waze.com/ul',
            parkingKnown: false,
        ), TripDecider::Maggie, $this->at('2026-11-03 09:00:00'));
        $this->em()->persist($trip);
        $this->em()->flush();
    }
}
