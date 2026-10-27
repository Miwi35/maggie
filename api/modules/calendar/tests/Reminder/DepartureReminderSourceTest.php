<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Reminder;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Enum\TripDecider;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Reminder\DepartureReminderSource;
use Maggie\Calendar\Repository\TripRepository;
use Maggie\Calendar\Routing\RouteLeg;
use Maggie\Calendar\Service\TripEstimate;
use Maggie\Calendar\Service\TripOrigin;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\Push\PushMessageFactory;
use Maggie\Notification\Repository\NotificationRepository;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * « Pars maintenant » (MAG-655): the departure alert the cron files when a trip's
 * `departAt` has come. The dentist is at 14:00 in Paris on 3 November 2026 (13:00
 * UTC), the trip leaves at 13:34 Paris (12:34 UTC).
 */
final class DepartureReminderSourceTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    private const NAVIGATION_URL = 'https://waze.com/ul?ll=48.119,-1.6672&navigate=yes';

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures(\dirname(__DIR__).'/Mcp/fixtures/EstimateTripToolTest.yaml');
    }

    public function testATripNotYetDueAlertsNobody(): void
    {
        $this->seed('dentist', '2026-11-03 13:34');

        $report = $this->source()->remind(new \DateTimeImmutable('2026-11-03 12:33:59 UTC'));

        self::assertSame(0, $report->created);
        self::assertSame([], $this->departures());
    }

    public function testADueTripBecomesADepartureNotificationFiledAgainstItsDepartAt(): void
    {
        $tripId = $this->seed('dentist', '2026-11-03 13:34');

        $report = $this->source()->remind(new \DateTimeImmutable('2026-11-03 12:34:00 UTC'));

        self::assertSame(1, $report->created);
        self::assertSame([], $report->warnings);
        $departures = $this->departures();
        self::assertCount(1, $departures);
        $notification = $departures[0];
        self::assertSame(NotificationType::Departure, $notification->getType());
        self::assertSame('Pars maintenant pour Dentiste', $notification->getTitle());
        self::assertSame('/api/trips/'.$tripId, $notification->getRelatedEntityIri());
        self::assertSame('2026-11-03T12:34:00+00:00', $notification->getOccurrenceStartAt()?->setTimezone(new \DateTimeZone('UTC'))->format('c'));
        self::assertSame('fixture@example.com', $notification->getUser()->getEmail());
        self::assertNull($notification->getReadAt());

        $this->assertMercureUpdatePublished('/api/notifications/');
        $this->assertElasticsearchIndexDispatched(Notification::class);
    }

    /** The wiring: the factory of the container finds the trip's URL through the calendar module. */
    public function testTheDepartureNotificationPushesWithTheTripsNavigationUrl(): void
    {
        $this->seed('dentist', '2026-11-03 13:34');
        $this->source()->remind(new \DateTimeImmutable('2026-11-03 12:34:00 UTC'));
        [$notification] = $this->departures();

        $message = self::getContainer()->get(PushMessageFactory::class)->build($notification, 'device-token');

        self::assertSame('departure', $message['data']['type']);
        self::assertSame(self::NAVIGATION_URL, $message['data']['link']);
        self::assertSame('Y aller', $message['data']['actionLabel']);
        self::assertSame('high', $message['android']['priority']);
        self::assertSame('departure', $message['android']['notification']['channel_id']);
    }

    public function testASecondPassAtTheSameDepartAtAlertsNobodyAgain(): void
    {
        $this->seed('dentist', '2026-11-03 13:34');

        $this->source()->remind(new \DateTimeImmutable('2026-11-03 12:34:00 UTC'));
        $second = $this->source()->remind(new \DateTimeImmutable('2026-11-03 12:35:00 UTC'));

        self::assertSame(0, $second->created);
        self::assertCount(1, $this->departures());
    }

    public function testADepartAtMovedAfterAnAlertAlertsAgainAtTheNewValue(): void
    {
        $tripId = $this->seed('dentist', '2026-11-03 13:34');
        $this->source()->remind(new \DateTimeImmutable('2026-11-03 12:34:00 UTC'));

        $this->moveTrip($tripId, '2026-11-03 13:45');
        $early = $this->source()->remind(new \DateTimeImmutable('2026-11-03 12:40:00 UTC'));
        $due = $this->source()->remind(new \DateTimeImmutable('2026-11-03 12:45:00 UTC'));
        $again = $this->source()->remind(new \DateTimeImmutable('2026-11-03 12:46:00 UTC'));

        self::assertSame([0, 1, 0], [$early->created, $due->created, $again->created]);
        $departAts = array_map(
            static fn (Notification $n): string => $n->getOccurrenceStartAt()?->setTimezone(new \DateTimeZone('UTC'))->format('H:i') ?? '',
            $this->departures(),
        );
        self::assertSame(['12:34', '12:45'], $departAts);
    }

    public function testAnEventAlreadyStartedIsNotAlertedFor(): void
    {
        $this->seed('dentist', '2026-11-03 13:34');

        $report = $this->source()->remind(new \DateTimeImmutable('2026-11-03 13:00:00 UTC'));

        self::assertSame(0, $report->created);
        self::assertSame([], $this->departures());
    }

    public function testACancelledEventIsNotAlertedFor(): void
    {
        $this->seed('dentist', '2026-11-03 13:34');
        $event = $this->em()->find(Event::class, $this->id('dentist'));
        self::assertInstanceOf(Event::class, $event);
        $event->setStatus(EventStatus::Cancelled);
        $this->em()->flush();

        $report = $this->source()->remind(new \DateTimeImmutable('2026-11-03 12:40:00 UTC'));

        self::assertSame(0, $report->created);
    }

    public function testEachOwnerIsAlertedForTheirOwnTrip(): void
    {
        $this->seed('dentist', '2026-11-03 13:34');
        $this->seed('other_dentist', '2026-11-03 13:30');

        $report = $this->source()->remind(new \DateTimeImmutable('2026-11-03 12:40:00 UTC'));

        self::assertSame(2, $report->created);
        $byOwner = [];
        foreach ($this->departures() as $notification) {
            $byOwner[$notification->getUser()->getEmail()] = $notification->getTitle();
        }
        self::assertEquals([
            'fixture@example.com' => 'Pars maintenant pour Dentiste',
            'other@example.com' => 'Pars maintenant pour Dentiste du voisin',
        ], $byOwner);
    }

    public function testAFailureForOneUserIsAWarningAndTheOthersAreAlerted(): void
    {
        $this->seed('dentist', '2026-11-03 13:34');
        $this->seed('other_dentist', '2026-11-03 13:30');
        $failing = $this->id('test_user');
        $bus = self::getContainer()->get(MessageBusInterface::class);
        $source = new DepartureReminderSource(
            self::getContainer()->get(TripRepository::class),
            self::getContainer()->get(NotificationRepository::class),
            new class($bus, $failing) implements MessageBusInterface {
                public function __construct(private readonly MessageBusInterface $bus, private readonly string $failing)
                {
                }

                public function dispatch(object $message, array $stamps = []): Envelope
                {
                    if ($message instanceof CreateNotificationCommand && $message->userId === $this->failing) {
                        throw new \RuntimeException('boom');
                    }

                    return $this->bus->dispatch($message, $stamps);
                }
            },
            new NullLogger(),
        );

        $report = $source->remind(new \DateTimeImmutable('2026-11-03 12:40:00 UTC'));

        self::assertSame(1, $report->created);
        self::assertCount(1, $report->warnings);
        self::assertStringContainsString('boom', $report->warnings[0]);
        $departures = $this->departures();
        self::assertCount(1, $departures);
        self::assertSame('other@example.com', $departures[0]->getUser()->getEmail());
    }

    private function source(): DepartureReminderSource
    {
        return self::getContainer()->get(DepartureReminderSource::class);
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** @return list<Notification> */
    private function departures(): array
    {
        $this->em()->clear();

        return $this->em()->getRepository(Notification::class)->findBy(['type' => NotificationType::Departure], ['createdAt' => 'ASC', 'id' => 'ASC']);
    }

    private function estimate(string $departAt): TripEstimate
    {
        return new TripEstimate(
            mode: TripMode::Walk,
            origin: new TripOrigin(TripOrigin::POSITION, null, 'ma position', 48.11, -1.68),
            steps: [new RouteLeg(TripMode::Walk, 240)],
            durationMinutes: 4,
            departAt: new \DateTimeImmutable($departAt, new \DateTimeZone('Europe/Paris')),
            navigationUrl: self::NAVIGATION_URL,
            parkingKnown: true,
        );
    }

    /** A trip as set_trip keeps it, leaving at $departAt (Paris). */
    private function seed(string $eventRef, string $departAt): string
    {
        $event = $this->em()->find(Event::class, $this->id($eventRef));
        self::assertInstanceOf(Event::class, $event);
        $trip = (new Trip($event))->apply($this->estimate($departAt), TripDecider::Maggie, new \DateTimeImmutable('2026-11-03 10:00:00 UTC'));
        $this->em()->persist($trip);
        $this->em()->flush();

        return (string) $trip->getId();
    }

    private function moveTrip(string $tripId, string $departAt): void
    {
        $this->em()->clear();
        $trip = $this->em()->find(Trip::class, $tripId);
        self::assertInstanceOf(Trip::class, $trip);
        $trip->apply($this->estimate($departAt), TripDecider::Maggie, new \DateTimeImmutable('2026-11-03 12:36:00 UTC'));
        $this->em()->flush();
    }
}
