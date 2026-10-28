<?php

namespace Maggie\Calendar\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\ProactionStatus;
use Maggie\Calendar\Enum\TripDecider;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Message\CreateEventCommand;
use Maggie\Calendar\Message\DeleteEventCommand;
use Maggie\Calendar\Message\ReplanTripsCommand;
use Maggie\Calendar\Message\ScheduleProactionCommand;
use Maggie\Calendar\Message\UpdateEventCommand;
use Maggie\Calendar\Routing\RouteLeg;
use Maggie\Calendar\Service\TripEstimate;
use Maggie\Calendar\Service\TripOrigin;
use Maggie\Core\Enum\LocationSource;
use Maggie\Core\Message\ParkCarCommand;
use Maggie\Core\Message\ReportUserLocationCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Uid\Ulid;

/**
 * « Partir à l'heure », part 7 (MAG-656): the real chain, from the save of an event or the report of a
 * position to the « Replanifier les trajets » proaction in the database. The clock reads 10:00 on
 * Tuesday 3 November 2026 in Paris; the dentist is at 14:00 that day, the user's home at 48.11, -1.68.
 */
class ReplanTripsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use ElasticsearchAssertionTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        // Loaded on a day no fixture event falls on, so the load itself triggers nothing.
        Clock::set(new MockClock('2020-01-01 12:00:00', 'Europe/Paris'));
        $this->loadFixtures(\dirname(__DIR__).'/Mcp/fixtures/EstimateTripToolTest.yaml');
        Clock::set(new MockClock('2026-11-03 10:00:00', 'Europe/Paris'));
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

    private function bus(): MessageBusInterface
    {
        return self::getContainer()->get('messenger.bus.default');
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    private function at(string $parisTime): \DateTimeImmutable
    {
        return new \DateTimeImmutable($parisTime, new \DateTimeZone('Europe/Paris'));
    }

    /** @return list<Proaction> the replan proactions in the database, whatever their status */
    private function replans(): array
    {
        $this->em()->clear();

        return $this->em()->getRepository(Proaction::class)->findBy(['summary' => ReplanTripsCommand::SUMMARY]);
    }

    private function seedTrip(string $eventRef, TripMode $mode = TripMode::Transit, string $departAt = '2026-11-03 13:20:00'): void
    {
        $event = $this->em()->find(Event::class, $this->id($eventRef));
        $drive = TripMode::Drive === $mode;
        $trip = (new Trip($event))->apply(new TripEstimate(
            mode: $mode,
            origin: new TripOrigin(TripOrigin::POSITION, null, 'ma position', 48.11, -1.68),
            steps: [new RouteLeg($mode, 600)],
            durationMinutes: 10,
            departAt: $this->at($departAt),
            navigationUrl: 'https://waze.com/ul',
            parkingKnown: false,
            carLatitude: $drive ? 48.1127 : null,
            carLongitude: $drive ? -1.68 : null,
        ), TripDecider::Maggie, $this->at('2026-11-03 09:00:00'));
        $this->em()->persist($trip);
        $this->em()->flush();
    }

    private function report(float $latitude, LocationSource $source = LocationSource::Gps, string $capturedAt = '2026-11-03 10:00:00'): void
    {
        $this->bus()->dispatch(new ReportUserLocationCommand($this->id('test_user'), $latitude, -1.68, 10.0, $this->at($capturedAt), $source));
    }

    private function parkCar(float $latitude): void
    {
        $this->bus()->dispatch(new ParkCarCommand($this->id('test_user'), $latitude, -1.68, $this->at('2026-11-03 10:00:00')));
    }

    private function update(string $ref, mixed ...$changes): void
    {
        $this->bus()->dispatch(new UpdateEventCommand($this->id($ref), ...$changes));
    }

    public function testMovingTheStartOfATodayEventFilesAReplanProactionForNow(): void
    {
        $this->update('dentist', startAt: $this->at('2026-11-03 15:00:00'), endAt: $this->at('2026-11-03 16:00:00'));

        $replans = $this->replans();
        self::assertCount(1, $replans);
        self::assertSame('Replanifier les trajets', $replans[0]->getSummary());
        self::assertSame(ProactionStatus::Pending, $replans[0]->getExecutionStatus());
        self::assertSame('2026-11-03 10:00:00', $replans[0]->getStartAt()->setTimezone(new \DateTimeZone('Europe/Paris'))->format('Y-m-d H:i:s'));
        self::assertSame($this->id('test_user'), (string) $replans[0]->getAgenda()->getUser()->getId());
    }

    public function testThePromptSaysWhyAndWhichDay(): void
    {
        $this->update('dentist', startAt: $this->at('2026-11-03 15:00:00'), endAt: $this->at('2026-11-03 16:00:00'));

        $prompt = $this->replans()[0]->getPrompt();
        self::assertStringContainsString('2026-11-03', $prompt);
        self::assertStringContainsString('l\'heure ou le lieu d\'un événement d\'aujourd\'hui vient de changer', $prompt);
    }

    public function testRelocatingATodayEventFilesAReplanProaction(): void
    {
        $this->update('dentist', location: '12 rue du Nouveau Cabinet Fictif');

        self::assertCount(1, $this->replans());
    }

    public function testRemovingATodayEventFilesAReplanProactionWhoseReasonIsTheRemoval(): void
    {
        $this->bus()->dispatch(new DeleteEventCommand($this->id('dentist')));

        $replans = $this->replans();
        self::assertCount(1, $replans);
        self::assertStringContainsString('vient d\'être supprimé', $replans[0]->getPrompt());
    }

    public function testRemovingATodayEventStillRemovesItFromTheIndexAndTellsTheScreens(): void
    {
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->bus()->dispatch(new DeleteEventCommand($this->id('dentist')));

        // The replan it filed flushed inside the removal's flush: the removal must not be forgotten.
        self::assertCount(1, $this->replans());
        $this->assertElasticsearchDeleteDispatched('events');
        self::assertContains(
            '/users/'.$this->id('test_user').'/api/events/'.$this->id('dentist'),
            array_merge(...array_map(static fn ($update) => $update->getTopics(), $this->getMercureHub()->getUpdates())),
        );
    }

    public function testCreatingATodayEventWithALocationFilesAReplanProaction(): void
    {
        $this->bus()->dispatch(new CreateEventCommand(
            summary: 'Coiffeur',
            startAt: $this->at('2026-11-03 17:00:00'),
            endAt: $this->at('2026-11-03 18:00:00'),
            agendaId: $this->id('own_agenda'),
            location: '3 rue des Ciseaux Fictifs',
        ));

        $replans = $this->replans();
        self::assertCount(1, $replans);
        self::assertStringContainsString('vient d\'être créé', $replans[0]->getPrompt());
    }

    public function testCreatingATodayEventWithoutALocationFilesNothing(): void
    {
        $this->bus()->dispatch(new CreateEventCommand(
            summary: 'Appel',
            startAt: $this->at('2026-11-03 17:00:00'),
            endAt: $this->at('2026-11-03 18:00:00'),
            agendaId: $this->id('own_agenda'),
        ));

        self::assertSame([], $this->replans());
    }

    public function testAnEventOfAnotherDayFilesNothing(): void
    {
        $this->bus()->dispatch(new CreateEventCommand(
            summary: 'Coiffeur',
            startAt: $this->at('2026-11-05 17:00:00'),
            endAt: $this->at('2026-11-05 18:00:00'),
            agendaId: $this->id('own_agenda'),
            location: '3 rue des Ciseaux Fictifs',
        ));

        self::assertSame([], $this->replans());
    }

    public function testChangingOnlyTheTitleOrTheDescriptionFilesNothing(): void
    {
        $this->update('dentist', summary: 'Dentiste d\'Ael');
        $this->update('dentist', description: 'Détartrage');

        self::assertSame([], $this->replans());
    }

    public function testSavingAnEventWithoutChangeFilesNothing(): void
    {
        $this->update('dentist', startAt: $this->at('2026-11-03 14:00:00'), endAt: $this->at('2026-11-03 15:00:00'), location: '5 allée du Sourire Fictif, 35000 Rennes');

        self::assertSame([], $this->replans());
    }

    public function testFilingOrMovingAProactionFilesNothing(): void
    {
        $this->bus()->dispatch(new ScheduleProactionCommand($this->id('test_user'), 'Rappelle-moi le dentiste', $this->at('2026-11-03 16:00:00'), 'Rappel'));
        $proaction = $this->em()->getRepository(Proaction::class)->findOneBy(['summary' => 'Rappel']);
        self::assertNotNull($proaction);

        $this->update('dentist', summary: 'Dentiste');
        $this->bus()->dispatch(new UpdateEventCommand((string) $proaction->getId(), startAt: $this->at('2026-11-03 17:00:00'), endAt: $this->at('2026-11-03 17:05:00')));

        self::assertSame([], $this->replans());
        self::assertCount(1, $this->em()->getRepository(Proaction::class)->findAll(), 'the proaction alone, no echo of its own');
    }

    public function testAPositionTwoKilometresFromTheNextTripOriginFilesAReplanProaction(): void
    {
        $this->seedTrip('dentist');

        $this->report(48.128);

        $replans = $this->replans();
        self::assertCount(1, $replans);
        self::assertStringContainsString('plus de 300 m', $replans[0]->getPrompt());
    }

    public function testAPositionAHundredMetresAwayFilesNothing(): void
    {
        $this->seedTrip('dentist');

        $this->report(48.1109);

        self::assertSame([], $this->replans());
    }

    /** MAG-666, recette of 28 Oct.: a vague network fix 2 km off must not undo the GPS position. */
    public function testANetworkFixFarFromTheGpsPositionFilesNothing(): void
    {
        $this->seedTrip('dentist');
        $this->report(48.1105);

        $this->report(48.128, LocationSource::Network, '2026-11-03 10:05:00');

        self::assertSame([], $this->replans());
    }

    public function testANetworkPositionAloneFarFromTheOriginFilesAReplanProaction(): void
    {
        $this->seedTrip('dentist');

        $this->report(48.128, LocationSource::Network);

        self::assertCount(1, $this->replans());
    }

    public function testAPositionWithoutATripLeftTodayFilesNothing(): void
    {
        $this->report(48.128);
        self::assertSame([], $this->replans(), 'no trip at all');

        // A trip that left an hour ago, and one that leaves tomorrow.
        $this->seedTrip('dentist', departAt: '2026-11-03 09:00:00');
        $this->seedTrip('office', departAt: '2026-11-04 08:00:00');
        $this->report(48.14);

        self::assertSame([], $this->replans());
    }

    public function testACarMovedFarFromTheOneOfTheNextTripByCarFilesAReplanProaction(): void
    {
        $this->seedTrip('dentist', TripMode::Drive);

        $this->parkCar(48.1172);

        $replans = $this->replans();
        self::assertCount(1, $replans);
        self::assertStringContainsString('voiture', $replans[0]->getPrompt());
    }

    public function testACarMovedWithOnlyATripByTransitLeftFilesNothing(): void
    {
        $this->seedTrip('dentist', TripMode::Transit);

        $this->parkCar(48.1172);

        self::assertSame([], $this->replans());
    }

    public function testACarMovedFilesAReplanWhenALaterTripIsByCar(): void
    {
        $this->seedTrip('dentist', TripMode::Transit, '2026-11-03 13:20:00');
        $this->seedTrip('office', TripMode::Drive, '2026-11-03 17:00:00');

        $this->parkCar(48.1172);

        self::assertCount(1, $this->replans());
    }

    public function testTwoTriggersWhileOneIsPendingFileOneProaction(): void
    {
        $this->update('dentist', location: '12 rue du Nouveau Cabinet Fictif');
        $this->update('dentist', startAt: $this->at('2026-11-03 15:00:00'), endAt: $this->at('2026-11-03 16:00:00'));
        $this->bus()->dispatch(new DeleteEventCommand($this->id('swim')));

        self::assertCount(1, $this->replans());
    }

    public function testOnceItRanANewTriggerFilesAnotherOne(): void
    {
        $this->update('dentist', location: '12 rue du Nouveau Cabinet Fictif');
        $first = $this->replans()[0];
        $first->markRunning();
        $this->em()->flush();

        $this->update('dentist', startAt: $this->at('2026-11-03 15:00:00'), endAt: $this->at('2026-11-03 16:00:00'));
        self::assertCount(2, $this->replans(), 'a run that has started has already read the day');

        foreach ($this->replans() as $replan) {
            $replan->markCompleted('Trajets replanifiés.', $this->at('2026-11-03 10:01:00'));
        }
        $this->em()->flush();
        $this->update('dentist', location: '1 rue du Dernier Cabinet Fictif');

        self::assertCount(3, $this->replans());
    }

    public function testAnotherUsersPendingReplanDoesNotHoldBackThisOne(): void
    {
        $this->bus()->dispatch(new ReplanTripsCommand($this->id('other_user'), ReplanTripsCommand::EVENT_UPDATED));

        $this->update('dentist', location: '12 rue du Nouveau Cabinet Fictif');

        self::assertCount(2, $this->replans());
    }

    public function testAUserWhoIsGoneIsIgnored(): void
    {
        $this->bus()->dispatch(new ReplanTripsCommand((string) new Ulid(), ReplanTripsCommand::EVENT_UPDATED));

        self::assertSame([], $this->replans());
    }
}
