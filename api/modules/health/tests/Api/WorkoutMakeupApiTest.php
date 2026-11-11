<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Entity\WorkoutMakeup;
use Maggie\Health\Enum\MakeupOption;
use Maggie\Health\Enum\WorkoutMakeupStatus;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Reminder\MissedWorkoutReminderSource;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Message\WithdrawPushNotificationCommand;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * Sessions kept, the streak and the make-up of a missed one (MAG-771), through
 * the API: the fixture's Tuesday run, 18:30 Paris for 45 min, is missed from
 * 19:45 (18:45 UTC); three planned sessions were kept before it.
 */
final class WorkoutMakeupApiTest extends WebTestCase
{
    use AuthenticatedTestTrait;
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    private const MISSED_AT = '2026-11-10 18:46:00 UTC';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        Clock::set(new MockClock('2026-11-10 06:00:00 UTC'));
        $this->loadFixtures('workout_makeup.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testASessionDoneTheSameDayKeepsThePlannedOneAndTheStreakGoesOn(): void
    {
        $this->createDone('2026-11-10T07:00:00+01:00');

        self::assertResponseStatusCodeSame(201);
        self::assertSame('/api/workouts/'.$this->id('tuesday'), $this->response()['keeps']);
        self::assertSame(4, $this->response()['streak']);
        $done = $this->keeperOf('tuesday');
        self::assertNotNull($done);
        self::assertSame(4, $done->getStreak());
        $this->assertMercureUpdatePublished('/api/workouts/');
    }

    public function testASessionDoneAnotherDayKeepsNothing(): void
    {
        $this->createDone('2026-11-11T07:00:00+01:00');

        self::assertResponseStatusCodeSame(201);
        self::assertNull($this->response()['keeps'] ?? null);
        self::assertNull($this->keeperOf('tuesday'));
    }

    public function testAMissedSessionIsProposedAMakeupOnceFromHalfAnHourAfterItsEnd(): void
    {
        self::assertSame(0, $this->remind('2026-11-10 18:44:59 UTC')->created);
        self::assertSame(1, $this->remind('2026-11-10 18:45:00 UTC')->created);
        self::assertSame(0, $this->remind('2026-11-10 18:46:00 UTC')->created);

        $makeup = $this->makeupOf('tuesday');
        self::assertNotNull($makeup);
        self::assertSame(WorkoutMakeupStatus::Proposed, $makeup->getStatus());
        self::assertSame(45, $makeup->getSofterMinutes());
        self::assertSame(20, $makeup->getShorterMinutes());
        self::assertSame(3, $makeup->getStreak());

        $notifications = $this->notifications();
        self::assertCount(1, $notifications);
        self::assertSame('Séance de 18 h 30 manquée, monsieur.', $notifications[0]->getTitle());
        self::assertSame('Pour garder votre série de 3, ce soir :', $notifications[0]->getBody());
        self::assertSame(['softer', 'shorter', 'tomorrow'], $notifications[0]->getActions());
        self::assertSame(WorkoutMakeup::iri($makeup), $notifications[0]->getRelatedEntityIri());
        $this->assertMercureUpdatePublished('/api/workout-makeups/');
    }

    public function testTooLateTonightOnlyTomorrowIsOffered(): void
    {
        // 22:45 Paris: neither 45 nor 20 min can end by 23:00.
        $this->remind('2026-11-10 21:45:00 UTC');

        $notifications = $this->notifications();
        self::assertCount(1, $notifications);
        self::assertSame(['tomorrow'], $notifications[0]->getActions());
        self::assertSame('Pour garder votre série de 3, demain :', $notifications[0]->getBody());
    }

    public function testASessionMissedLongAgoIsNoLongerProposed(): void
    {
        self::assertSame(0, $this->remind('2026-11-11 07:00:00 UTC')->created);
    }

    public function testASessionDoneLaterTheSameDayOvertakesTheProposal(): void
    {
        $this->remind(self::MISSED_AT);
        $this->resetMercure();

        $this->createDone('2026-11-10T20:00:00+01:00');

        self::assertSame(4, $this->response()['streak']);
        self::assertSame(WorkoutMakeupStatus::Obsolete, $this->makeupOf('tuesday')?->getStatus());
        $notification = $this->notifications()[0];
        self::assertNotNull($notification->getReadAt());
        self::assertSame([(string) $notification->getId()], $this->withdrawals());
        $this->assertMercureUpdatePublished('/api/workout-makeups/');
    }

    public function testChoosingShorterPlansTwentyMinutesTonightAndKeepingItKeepsTheStreak(): void
    {
        $this->remind(self::MISSED_AT);
        $makeup = $this->makeupOf('tuesday');
        self::assertNotNull($makeup);
        $this->resetMercure();

        $this->choose((string) $makeup->getId(), ['option' => 'shorter']);

        self::assertResponseIsSuccessful();
        $body = $this->response();
        self::assertSame('chosen', $body['status']);
        self::assertSame('shorter', $body['chosenOption']);
        self::assertSame('/api/workouts/'.$this->id('tuesday'), $body['missed']);
        $planned = $this->em()->getRepository(Workout::class)->findOneBy(['makeupOf' => $this->id('tuesday')]);
        self::assertInstanceOf(Workout::class, $planned);
        self::assertSame('/api/workouts/'.$planned->getId(), $body['chosenWorkout']);
        self::assertSame(WorkoutSource::Planned, $planned->getSource());
        self::assertSame(MakeupOption::Shorter, $planned->getMakeupOption());
        self::assertSame(20, $planned->getDurationMinutes());
        self::assertSame('Fractionné', $planned->getTitle());
        // 19:46 Paris: the next quarter hour.
        self::assertEquals(new \DateTimeImmutable('2026-11-10T20:00:00+01:00'), $planned->getStartedAt());
        self::assertNotNull($this->notifications()[0]->getReadAt());
        self::assertCount(1, $this->withdrawals());
        $this->assertMercureUpdatePublished('/api/workout-makeups/');
        $this->assertMercureUpdatePublished('/api/workouts/');

        $this->createDone('2026-11-10T20:05:00+01:00');

        self::assertSame('/api/workouts/'.$planned->getId(), $this->response()['keeps']);
        self::assertSame(4, $this->response()['streak']);
    }

    public function testChoosingTomorrowPlansTheSameSessionAtTheSameHour(): void
    {
        $this->remind(self::MISSED_AT);

        $this->choose((string) $this->makeupOf('tuesday')?->getId(), ['option' => 'tomorrow']);

        self::assertResponseIsSuccessful();
        $planned = $this->em()->getRepository(Workout::class)->findOneBy(['makeupOf' => $this->id('tuesday')]);
        self::assertInstanceOf(Workout::class, $planned);
        self::assertSame(45, $planned->getDurationMinutes());
        self::assertEquals(new \DateTimeImmutable('2026-11-11T18:30:00+01:00'), $planned->getStartedAt());
    }

    public function testAMakeupNotKeptIsNotMadeUpAndTheNextStreakStartsAgainAtOne(): void
    {
        $this->remind(self::MISSED_AT);
        $this->choose((string) $this->makeupOf('tuesday')?->getId(), ['option' => 'shorter']);

        // The make-up, 20:00 to 20:20 Paris, is missed from 20:50 (19:50 UTC).
        self::assertSame(0, $this->remind('2026-11-10 20:00:00 UTC')->created);
        self::assertCount(1, $this->em()->getRepository(WorkoutMakeup::class)->findBy(['user' => $this->getFixture('test_user')]));

        Clock::set(new MockClock('2026-11-12 08:00:00 UTC'));
        $this->createDone('2026-11-12T07:00:00+01:00');

        self::assertSame('/api/workouts/'.$this->id('thursday'), $this->response()['keeps']);
        self::assertSame(1, $this->response()['streak']);
    }

    public function testAProposalIsReadLikeAnyResource(): void
    {
        $this->remind(self::MISSED_AT);
        $makeup = $this->makeupOf('tuesday');
        self::assertNotNull($makeup);

        $this->login();
        $this->client->request('GET', WorkoutMakeup::iri($makeup), server: ['HTTP_ACCEPT' => 'application/ld+json', ...$this->authHeaders()]);

        self::assertResponseIsSuccessful();
        $body = $this->response();
        self::assertSame('proposed', $body['status']);
        self::assertSame(45, $body['softerMinutes']);
        self::assertSame(20, $body['shorterMinutes']);
        self::assertSame(3, $body['streak']);
        self::assertNull($body['chosenOption'] ?? null);
    }

    public function testAnotherUsersProposalIsNotFound(): void
    {
        $other = WorkoutMakeup::iri($this->getFixture('other_makeup'));

        $this->login();
        $this->client->request('GET', $other, server: ['HTTP_ACCEPT' => 'application/ld+json', ...$this->authHeaders()]);
        self::assertResponseStatusCodeSame(404);

        $this->choose((string) $this->getFixture('other_makeup')->getId(), ['option' => 'tomorrow']);
        self::assertResponseStatusCodeSame(404);
        self::assertSame(WorkoutMakeupStatus::Proposed, $this->em()->find(WorkoutMakeup::class, $this->getFixture('other_makeup')->getId())?->getStatus());
    }

    public function testChoosingTwiceIsAConflict(): void
    {
        $this->remind(self::MISSED_AT);
        $id = (string) $this->makeupOf('tuesday')?->getId();
        $this->choose($id, ['option' => 'shorter']);
        $this->resetMercure();

        $this->choose($id, ['option' => 'softer']);

        self::assertResponseStatusCodeSame(409);
        self::assertCount(1, $this->em()->getRepository(Workout::class)->findBy(['makeupOf' => $this->id('tuesday')]));
        $this->assertMercureUpdateCount(0);
    }

    public function testAnUnknownOptionIsRefused(): void
    {
        $this->remind(self::MISSED_AT);
        $this->resetMercure();

        $this->choose((string) $this->makeupOf('tuesday')?->getId(), ['option' => 'later']);

        self::assertResponseStatusCodeSame(400);
        self::assertSame('option', $this->response()['field']);
        self::assertSame(WorkoutMakeupStatus::Proposed, $this->makeupOf('tuesday')?->getStatus());
        $this->assertMercureUpdateCount(0);
    }

    public function testWithoutAuthenticationChoosingIsRefused(): void
    {
        $this->client->request('POST', '/api/workout-makeups/'.$this->getFixture('other_makeup')->getId().'/choose', server: ['CONTENT_TYPE' => 'application/json'], content: '{"option":"tomorrow"}');

        self::assertResponseStatusCodeSame(401);
    }

    private function login(): void
    {
        $user = $this->getFixture('test_user');
        \assert($user instanceof User);
        $this->authenticateAsUser($user);
    }

    private function createDone(string $startedAt): void
    {
        $this->login();
        $this->client->request('POST', '/api/workouts', server: [
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
            ...$this->authHeaders(),
        ], content: json_encode(['type' => 'run', 'title' => 'Course', 'startedAt' => $startedAt, 'durationMinutes' => 30, 'source' => 'manual'], JSON_THROW_ON_ERROR));
    }

    /** @param array<string, mixed> $body */
    private function choose(string $id, array $body): void
    {
        $this->login();
        $this->client->request('POST', '/api/workout-makeups/'.$id.'/choose', server: [
            'CONTENT_TYPE' => 'application/json',
            'HTTP_ACCEPT' => 'application/json',
            ...$this->authHeaders(),
        ], content: json_encode($body, JSON_THROW_ON_ERROR));
    }

    private function remind(string $now): \Maggie\Core\Reminder\ReminderReport
    {
        Clock::set(new MockClock($now));
        $source = self::getContainer()->get(MissedWorkoutReminderSource::class);
        \assert($source instanceof MissedWorkoutReminderSource);

        return $source->remind(new \DateTimeImmutable($now));
    }

    /** @return array<string, mixed> */
    private function response(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function keeperOf(string $fixture): ?Workout
    {
        return $this->em()->getRepository(Workout::class)->findOneBy(['keeps' => $this->id($fixture)]);
    }

    private function makeupOf(string $fixture): ?WorkoutMakeup
    {
        return $this->em()->getRepository(WorkoutMakeup::class)->findOneBy(['missed' => $this->id($fixture)]);
    }

    /** @return list<Notification> */
    private function notifications(): array
    {
        return $this->em()->getRepository(Notification::class)->findBy(['type' => NotificationType::WorkoutMakeup], ['createdAt' => 'ASC']);
    }

    /** @return list<string> the notifications taken off the phones */
    private function withdrawals(): array
    {
        $messages = array_map(static fn ($envelope) => $envelope->getMessage(), $this->getAsyncTransport()->getSent());

        return array_values(array_map(
            static fn (WithdrawPushNotificationCommand $command): string => $command->notificationId,
            array_filter($messages, static fn (object $message): bool => $message instanceof WithdrawPushNotificationCommand),
        ));
    }
}
