<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Doctrine;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Events;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\DeleteAgendaCommand;
use Maggie\Calendar\Message\DeleteEventCommand;
use Maggie\Calendar\Message\UpdateEventCommand;
use Maggie\Core\Entity\User;
use Maggie\Health\Doctrine\PlannedWorkoutListener;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutType;
use Maggie\Health\Message\CreateWorkoutCommand;
use Maggie\Health\Message\DeleteWorkoutCommand;
use Maggie\Health\Message\UpdateWorkoutCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Uid\Ulid;

/**
 * A planned session and its event in the « Santé » agenda follow each other
 * (MAG-777), whichever side moves: the session through the API or Maggie, the
 * event through the agenda. No round trip without end.
 */
class PlannedWorkoutAgendaTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('planned_workout.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);

        return $em;
    }

    private function bus(): MessageBusInterface
    {
        $bus = self::getContainer()->get('messenger.bus.default');
        \assert($bus instanceof MessageBusInterface);

        return $bus;
    }

    private function userId(string $ref = 'test_user'): string
    {
        $user = $this->getFixture($ref);
        \assert($user instanceof User);

        return (string) $user->getId();
    }

    /** « Séance A », planned on 12 November at 18:30 for 30 minutes. */
    private function plan(string $ref = 'test_user'): string
    {
        $this->bus()->dispatch(new CreateWorkoutCommand(
            userId: $this->userId($ref),
            type: 'strength',
            title: 'Séance A',
            startedAt: '2026-11-12T18:30:00+01:00',
            durationMinutes: 30,
            source: 'planned',
        ));

        $this->em()->clear();
        $workout = $this->em()->getRepository(Workout::class)->findOneBy(['title' => 'Séance A', 'user' => $this->userId($ref)]);
        \assert($workout instanceof Workout);

        return (string) $workout->getId();
    }

    private function workout(string $id): ?Workout
    {
        $this->em()->clear();

        return $this->em()->find(Workout::class, $id);
    }

    private function eventOf(string $workoutId): ?Event
    {
        return $this->workout($workoutId)?->getEvent();
    }

    /** @return list<Event> */
    private function sportEvents(): array
    {
        $this->em()->clear();

        return $this->em()->getRepository(Event::class)->findBy(['activity' => Event::ACTIVITY_SPORT]);
    }

    private static function utc(?\DateTimeImmutable $at): ?string
    {
        return $at?->setTimezone(new \DateTimeZone('UTC'))->format(\DATE_ATOM);
    }

    public function testAPlannedSessionIsFiledInTheHealthAgendaCreatedAtFirstNeed(): void
    {
        $id = $this->plan();

        $event = $this->eventOf($id);
        self::assertNotNull($event);
        self::assertSame('Séance A', $event->getSummary());
        self::assertSame(Event::ACTIVITY_SPORT, $event->getActivity());
        self::assertSame('2026-11-12T17:30:00+00:00', self::utc($event->getStartAt()));
        self::assertSame('2026-11-12T18:00:00+00:00', self::utc($event->getEndAt()));
        self::assertNull($event->getRrule());
        self::assertFalse($event->isAllDay());

        $agenda = $event->getAgenda();
        self::assertSame(Agenda::MODULE_HEALTH, $agenda->getModule());
        self::assertSame('Santé', $agenda->getName());
        self::assertSame('#FF8A80', $agenda->getColor());
        self::assertNull($agenda->getGoogleCalendarId());
        self::assertSame($this->userId(), (string) $agenda->getUser()->getId());

        $this->assertMercurePublishedOnce('/api/events/'.$event->getId());
        $this->assertMercurePublishedOnce('/api/workouts/'.$id);
        $this->assertElasticsearchIndexDispatchedFor(Event::class, (string) $event->getId());
        self::assertSame('/api/events/'.$event->getId(), $this->workout($id)?->toMercurePayload()['event']);
    }

    public function testASecondSessionGoesInTheSameAgenda(): void
    {
        $this->plan();
        $this->bus()->dispatch(new CreateWorkoutCommand(userId: $this->userId(), type: 'run', title: 'Footing', startedAt: '2026-11-14T08:00:00+01:00', durationMinutes: 40, source: 'planned'));

        self::assertCount(2, $this->sportEvents());
        self::assertCount(1, $this->em()->getRepository(Agenda::class)->findBy(['module' => Agenda::MODULE_HEALTH]));
    }

    public function testASessionDoneGetsNoEvent(): void
    {
        $this->bus()->dispatch(new CreateWorkoutCommand(userId: $this->userId(), type: 'run', title: 'Footing', startedAt: '2026-11-10T08:00:00+01:00', durationMinutes: 40, source: 'manual'));

        self::assertSame([], $this->sportEvents());
        self::assertNull($this->em()->getRepository(Agenda::class)->findOneBy(['module' => Agenda::MODULE_HEALTH]));
    }

    public function testTheEventFollowsTheSessionChanged(): void
    {
        $id = $this->plan();
        $eventId = (string) $this->eventOf($id)?->getId();
        $this->resetMercure();

        $this->bus()->dispatch(new UpdateWorkoutCommand(userId: $this->userId(), workoutId: $id, title: 'Séance B', startedAt: '2026-11-12T19:00:00+01:00', durationMinutes: 45));

        $event = $this->eventOf($id);
        self::assertSame($eventId, (string) $event?->getId());
        self::assertSame('Séance B', $event?->getSummary());
        self::assertSame('2026-11-12T18:00:00+00:00', self::utc($event?->getStartAt()));
        self::assertSame('2026-11-12T18:45:00+00:00', self::utc($event?->getEndAt()));
        $this->assertMercurePublishedOnce('/api/events/'.$eventId);
    }

    public function testAChangeTheEventDoesNotShowLeavesItUntouched(): void
    {
        $id = $this->plan();
        $eventId = (string) $this->eventOf($id)?->getId();
        $this->resetMercure();

        $this->bus()->dispatch(new UpdateWorkoutCommand(userId: $this->userId(), workoutId: $id, notes: 'Avec le sac lesté'));

        $this->assertNothingPublishedOn('/api/events/'.$eventId);
    }

    public function testTheEventGoesWithTheSessionDeleted(): void
    {
        $id = $this->plan();
        $eventId = (string) $this->eventOf($id)?->getId();
        $this->resetMercure();

        $this->bus()->dispatch(new DeleteWorkoutCommand(userId: $this->userId(), workoutId: $id));

        self::assertNull($this->em()->find(Event::class, $eventId));
        $this->assertMercureDeletePublished('/api/events/'.$eventId);
        $this->assertMercureDeletePublished('/api/workouts/'.$id);
    }

    public function testASessionMarkedAsDoneLeavesTheAgenda(): void
    {
        $id = $this->plan();
        $eventId = (string) $this->eventOf($id)?->getId();

        $this->bus()->dispatch(new UpdateWorkoutCommand(userId: $this->userId(), workoutId: $id, source: 'manual'));

        $workout = $this->workout($id);
        self::assertSame(WorkoutSource::Manual, $workout?->getSource());
        self::assertNull($workout?->getEvent());
        self::assertNull($this->em()->find(Event::class, $eventId));
    }

    public function testTheSessionFollowsItsEventMovedInTheAgenda(): void
    {
        $id = $this->plan();
        $eventId = (string) $this->eventOf($id)?->getId();
        $this->resetMercure();

        $this->bus()->dispatch(new UpdateEventCommand(
            eventId: $eventId,
            startAt: new \DateTimeImmutable('2026-11-12T19:00:00+01:00'),
            endAt: new \DateTimeImmutable('2026-11-12T19:45:00+01:00'),
        ));

        $workout = $this->workout($id);
        self::assertSame('2026-11-12T18:00:00+00:00', self::utc($workout?->getStartedAt()));
        self::assertSame(45, $workout?->getDurationMinutes());
        self::assertSame('2026-11-12T18:45:00+00:00', self::utc($this->eventOf($id)?->getEndAt()));
        $this->assertMercurePublishedOnce('/api/workouts/'.$id);
        $this->assertMercurePublishedOnce('/api/events/'.$eventId);
    }

    public function testTheSessionGoesWithItsEventDeletedInTheAgenda(): void
    {
        $id = $this->plan();
        $eventId = (string) $this->eventOf($id)?->getId();
        $this->resetMercure();

        $this->bus()->dispatch(new DeleteEventCommand($eventId));

        self::assertNull($this->workout($id));
        $this->assertMercureDeletePublished('/api/workouts/'.$id);
        $this->assertMercureDeletePublished('/api/events/'.$eventId);
    }

    public function testASessionDoneKeepsWhatWasDoneWhenAnOldEventMoves(): void
    {
        $id = $this->plan();
        $event = $this->eventOf($id);
        \assert($event instanceof Event);
        // A session marked as done whose event outlived it (a failed effect): the event no longer rules it.
        $this->em()->getConnection()->executeStatement("UPDATE workout SET source = 'manual' WHERE id = ?", [Ulid::fromString($id)->toRfc4122()]);
        $this->em()->clear();

        $this->bus()->dispatch(new UpdateEventCommand(eventId: (string) $event->getId(), startAt: new \DateTimeImmutable('2026-11-12T20:00:00+01:00'), endAt: new \DateTimeImmutable('2026-11-12T20:30:00+01:00')));
        self::assertSame('2026-11-12T17:30:00+00:00', self::utc($this->workout($id)?->getStartedAt()));

        $this->bus()->dispatch(new DeleteEventCommand((string) $event->getId()));
        self::assertNotNull($this->workout($id));
    }

    public function testDeletingTheAgendaKeepsTheSessions(): void
    {
        $id = $this->plan();
        $agendaId = (string) $this->eventOf($id)?->getAgenda()->getId();

        $this->bus()->dispatch(new DeleteAgendaCommand($agendaId));

        $workout = $this->workout($id);
        self::assertNotNull($workout);
        self::assertNull($workout->getEvent());
    }

    public function testAnotherUsersSessionGoesInTheirOwnAgenda(): void
    {
        $this->eventOf($this->plan());
        $other = $this->eventOf($this->plan('other_user'));

        self::assertSame($this->userId('other_user'), (string) $other?->getAgenda()->getUser()->getId());
        self::assertCount(2, $this->em()->getRepository(Agenda::class)->findBy(['module' => Agenda::MODULE_HEALTH]));
    }

    public function testFixturesFileNothing(): void
    {
        $listener = self::getContainer()->get(PlannedWorkoutListener::class);
        \assert($listener instanceof PlannedWorkoutListener);
        $user = $this->em()->find(User::class, $this->userId());
        \assert($user instanceof User);

        $workout = (new Workout())
            ->setUser($user)
            ->setType(WorkoutType::Strength)
            ->setTitle('Séance A')
            ->setStartedAt(new \DateTimeImmutable('2026-11-12T18:30:00+01:00'))
            ->setDurationMinutes(30)
            ->setSource(WorkoutSource::Planned);
        $listener->withoutEffects(function () use ($workout): void {
            $this->em()->persist($workout);
            $this->em()->flush();
        });

        self::assertSame([], $this->sportEvents());
    }

    public function testAFlushThatFailedFilesNothingLater(): void
    {
        $user = $this->em()->find(User::class, $this->userId());
        \assert($user instanceof User);
        $this->em()->persist((new Workout())
            ->setUser($user)
            ->setType(WorkoutType::Strength)
            ->setTitle('Séance A')
            ->setStartedAt(new \DateTimeImmutable('2026-11-12T18:30:00+01:00'))
            ->setDurationMinutes(30)
            ->setSource(WorkoutSource::Planned));
        // A listener registered after this one makes the flush fail once the session is noted.
        $boom = new class {
            public function onFlush(): void
            {
                throw new \RuntimeException('flush failed');
            }
        };
        $this->em()->getEventManager()->addEventListener([Events::onFlush], $boom);
        try {
            $this->em()->flush();
            self::fail('The flush should have failed.');
        } catch (\RuntimeException) {
        }
        $this->em()->getEventManager()->removeEventListener([Events::onFlush], $boom);

        $this->em()->clear();
        $done = $this->em()->find(Workout::class, (string) $this->getFixture('done')->getId());
        \assert($done instanceof Workout);
        $done->setNotes('Bonnes jambes');
        $this->em()->flush();

        self::assertSame([], $this->sportEvents());
    }
}
