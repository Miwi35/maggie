<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Reward;
use Maggie\Calendar\Entity\Routine;
use Maggie\Calendar\Entity\RoutineCheck;
use Maggie\Calendar\Entity\TaskReminder;
use Maggie\Calendar\Enum\DayMoment;
use Maggie\Calendar\Service\ReminderMessages;
use Maggie\Calendar\Service\TaskReminders;
use Maggie\Core\Entity\User;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * Routine reminders (MAG-512): at each moment and each hour, one grouped
 * reminder for the routines due, not ticked and asking to be reminded — the
 * words of the tone set, the daily cap and the quiet hours kept, « Fait » when
 * one routine is left, and nothing when all are ticked meanwhile.
 *
 * Paris is UTC+2 on 2026-10-20: 18:00 UTC is 20:00, `afterDinner`.
 */
final class RoutineReminderApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string TODAY = '2026-10-20';
    private const string AFTER_DINNER = '2026-10-20 18:00:00 UTC';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        $this->at(self::AFTER_DINNER);
        $this->loadFixtures('RoutineReminderApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testAtAMomentTheDueRoutinesOfItGetOneGroupedReminderFromTheBankOfTheToneSet(): void
    {
        self::assertSame(1, $this->remind(), 'two routines, one reminder — and not the one that asks for none, nor the other user\'s');

        $reminder = $this->onlyReminder();
        self::assertSame(TaskReminder::KIND_ROUTINE, $reminder->getKind());
        self::assertSame(DayMoment::AfterDinner, $reminder->getMoment());
        self::assertSame(self::TODAY.':afterDinner', $reminder->getSubjectId());
        self::assertFalse($reminder->isSnoozed());
        self::assertNotNull($reminder->getSentAt());
        self::assertStringStartsWith('routine.doux.', (string) $reminder->getMessageKey());

        $notification = $reminder->getNotification();
        self::assertInstanceOf(Notification::class, $notification);
        self::assertSame(NotificationType::Routine, $notification->getType());
        self::assertSame('Routines · soir', $notification->getTitle());
        self::assertContains($notification->getBody(), self::bank(ReminderMessages::ROUTINE['doux'], 'Brosser les dents, Passer le fil dentaire'));
        self::assertSame(['open', 'in10'], $notification->getActions());
        self::assertSame((string) $reminder->getId(), $notification->getReminderId());
        self::assertSame('/tasks', $notification->getRelatedEntityIri());
        $this->assertMercureUpdatePublished('/api/notifications/');
        $this->assertElasticsearchIndexDispatched(Notification::class);

        $this->at('2026-10-20 18:01:00 UTC');
        self::assertSame(0, $this->remind(), 'once per moment and per day');
        self::assertCount(1, $this->reminders());
    }

    public function testTheToneSetDecidesTheBank(): void
    {
        $this->settings(['tone' => 'drole']);

        $this->remind();

        $reminder = $this->onlyReminder();
        self::assertStringStartsWith('routine.drole.', (string) $reminder->getMessageKey());
        self::assertContains($reminder->getNotification()?->getBody(), self::bank(ReminderMessages::ROUTINE['drole'], 'Brosser les dents, Passer le fil dentaire'));
    }

    public function testTheMorningMomentIsTitledMatin(): void
    {
        $this->settings(['dayMoments' => ['wake' => '08:15']]);
        $this->at('2026-10-20 06:15:00 UTC');

        self::assertSame(1, $this->remind(), '08:15, waking: the quiet hours are over');

        $reminder = $this->onlyReminder();
        self::assertSame(DayMoment::Wake, $reminder->getMoment());
        self::assertSame('Routines · matin', $reminder->getNotification()?->getTitle());
        self::assertSame(['done', 'in10', 'open'], $reminder->getNotification()->getActions(), 'one routine left: « Fait » first');
        self::assertContains($reminder->getNotification()->getBody(), self::bank(ReminderMessages::ROUTINE['doux'], 'Grand verre d\'eau'));
    }

    public function testARoutineAnchoredToAnHourHasItsOwnReminderAtThatHour(): void
    {
        $this->request('POST', '/api/routines', ['title' => 'Prendre le médicament', 'anchor' => 'time', 'anchorTime' => '20:00', 'recurrence' => ['kind' => 'daily'], 'remind' => true]);
        self::assertResponseStatusCodeSame(201);

        self::assertSame(2, $this->remind(), 'the moment and the hour, side by side');

        $subjects = array_map(static fn (TaskReminder $r): string => $r->getSubjectId(), $this->reminders());
        sort($subjects);
        self::assertSame([self::TODAY.':afterDinner', self::TODAY.':time@20:00'], $subjects);
        $titles = array_map(static fn (TaskReminder $r): ?string => $r->getNotification()?->getTitle(), $this->reminders());
        self::assertSame(['Routines · soir', 'Routines · soir'], $titles);
    }

    public function testRoutinesThatAskForNoReminderAreNeverReminded(): void
    {
        foreach (['teeth', 'floss'] as $ref) {
            $this->em()->find(Routine::class, $this->getFixture($ref)->getId())?->setRemind(false);
        }
        $this->em()->flush();
        $this->em()->clear();

        self::assertSame(0, $this->remind());
        $this->settings(['dayMoments' => ['wake' => '08:15']]);
        $this->at('2026-10-20 06:15:00 UTC');
        $this->em()->find(Routine::class, $this->getFixture('water')->getId())?->setRemind(false);
        $this->em()->flush();
        self::assertSame(0, $this->remind());

        self::assertSame([], $this->reminders());
        self::assertSame([], $this->notifications());
    }

    public function testNothingGoesOutWhenAllAreTickedAlready(): void
    {
        foreach (['teeth', 'floss'] as $ref) {
            $this->request('PUT', '/api/routines/'.$this->id($ref).'/checks/'.self::TODAY);
            self::assertResponseIsSuccessful();
        }

        self::assertSame(0, $this->remind());

        self::assertSame([], $this->reminders());
        self::assertSame([], $this->notifications());
    }

    public function testARoutineTickedBeforeTheMomentIsLeftOutOfTheList(): void
    {
        $this->request('PUT', '/api/routines/'.$this->id('floss').'/checks/'.self::TODAY);

        self::assertSame(1, $this->remind());

        $notification = $this->onlyReminder()->getNotification();
        self::assertContains($notification?->getBody(), self::bank(ReminderMessages::ROUTINE['doux'], 'Brosser les dents'));
        self::assertSame(['done', 'in10', 'open'], $notification->getActions());
    }

    public function testTheDailyCapIsSpentByARoutineReminder(): void
    {
        $this->settings(['reminderDailyCap' => 1]);
        $this->sentEarlierToday();

        self::assertSame(0, $this->remind(), 'the cap of 1 is already spent');

        self::assertSame([], $this->notifications());
    }

    public function testARoutineReminderCountsTowardTheCapOfTheOthers(): void
    {
        $this->settings(['reminderDailyCap' => 1]);
        self::assertSame(1, $this->remind());
        $this->request('POST', '/api/routines', ['title' => 'Prendre le médicament', 'anchor' => 'time', 'anchorTime' => '20:05', 'recurrence' => ['kind' => 'daily'], 'remind' => true]);

        $this->at('2026-10-20 18:05:00 UTC');
        self::assertSame(0, $this->remind());

        self::assertCount(1, $this->notifications());
    }

    public function testNothingGoesOutInTheQuietHours(): void
    {
        $this->settings(['dayMoments' => ['afterDinner' => '21:30']]);
        $this->at('2026-10-20 19:30:00 UTC');

        self::assertSame(0, $this->remind(), '21:30 is quiet');

        self::assertSame([], $this->notifications());
    }

    public function testDoneOnASingleRoutineTicksItAndSaysNoBravo(): void
    {
        $this->request('PUT', '/api/routines/'.$this->id('floss').'/checks/'.self::TODAY);
        $this->remind();
        $reminder = $this->onlyReminder();
        $notificationId = (string) $reminder->getNotification()?->getId();
        $this->resetMercure();

        $response = $this->act($reminder, 'done');

        self::assertResponseIsSuccessful();
        self::assertSame('done', $response['reminder']['action']);
        self::assertNull($response['reward'], 'no star, no bravo for a routine');
        self::assertSame([self::TODAY], $this->storedChecks('teeth'));
        self::assertSame([self::TODAY], $this->storedChecks('floss'));
        self::assertSame([], $this->em()->getRepository(Reward::class)->findAll());
        $check = $this->em()->getRepository(RoutineCheck::class)->findOneBy(['routine' => $this->id('teeth')]);
        \assert($check instanceof RoutineCheck);
        $this->assertMercurePublishedOnce('/api/routine_checks/'.$check->getId());
        $answered = $this->em()->find(Notification::class, $notificationId);
        self::assertNotNull($answered?->getReadAt());
        self::assertSame([], $answered->getActions(), 'nothing left to press');
        self::assertSame('done', $this->onlyReminder()->getAction());
    }

    public function testDoneIsRefusedWhenSeveralRoutinesWait(): void
    {
        $this->remind();
        $reminder = $this->onlyReminder();
        self::assertSame(['open', 'in10'], $reminder->getNotification()?->getActions());

        $this->act($reminder, 'done');

        self::assertResponseStatusCodeSame(409);
        self::assertSame([], $this->storedChecks('teeth'));
        self::assertSame([], $this->storedChecks('floss'));
        self::assertNull($this->onlyReminder()->getAction(), 'still to answer');
    }

    public function testOpenAnswersTheReminderWithoutTickingAnything(): void
    {
        $this->remind();
        $reminder = $this->onlyReminder();

        $response = $this->act($reminder, 'open');

        self::assertResponseIsSuccessful();
        self::assertSame('open', $response['reminder']['action']);
        self::assertNull($response['next']);
        self::assertNotNull($this->onlyReminder()->getNotification()?->getReadAt());
        self::assertSame([], $this->storedChecks('teeth'));
    }

    public function testIn10PutsItOffAndAskedAgainItSendsTheRoutinesStillToTick(): void
    {
        $this->settings(['reminderDailyCap' => 1]);
        $this->remind();
        $reminder = $this->onlyReminder();
        $notificationId = (string) $reminder->getNotification()?->getId();
        $this->resetMercure();

        $this->at('2026-10-20 18:01:00 UTC');
        $response = $this->act($reminder, 'in10');

        self::assertResponseIsSuccessful();
        self::assertTrue($response['next']['snoozed']);
        self::assertSame('2026-10-20T20:11:00+02:00', $response['next']['scheduledFor']);
        self::assertSame(self::TODAY.':afterDinner', $this->reminders()[1]->getSubjectId());
        self::assertNull($this->em()->find(Notification::class, $notificationId));
        $this->assertMercureDeletePublished('/api/notifications/'.$notificationId);

        $this->request('PUT', '/api/routines/'.$this->id('teeth').'/checks/'.self::TODAY);
        $this->at('2026-10-20 18:11:00 UTC');
        self::assertSame(1, $this->remind(), 'a reminder put off comes back, outside the cap');

        $back = $this->reminders()[1];
        self::assertTrue($back->isSnoozed());
        self::assertSame(NotificationType::Routine, $back->getNotification()?->getType());
        self::assertSame(['done', 'in10', 'open'], $back->getNotification()->getActions(), 'only the floss is left');
        self::assertContains($back->getNotification()->getBody(), self::bank(ReminderMessages::ROUTINE['doux'], 'Passer le fil dentaire'));
    }

    public function testAReminderPutOffIsDroppedWhenEverythingIsTickedMeanwhile(): void
    {
        $this->remind();
        $reminder = $this->onlyReminder();
        $this->act($reminder, 'in10');
        foreach (['teeth', 'floss'] as $ref) {
            $this->request('PUT', '/api/routines/'.$this->id($ref).'/checks/'.self::TODAY);
        }

        $this->at('2026-10-20 18:10:00 UTC');
        self::assertSame(0, $this->remind());

        $snoozed = $this->reminders()[1];
        self::assertNull($snoozed->getSentAt());
        self::assertNotNull($snoozed->getDroppedAt());
        self::assertSame([], $this->notifications());
    }

    public function testAReminderIsAnsweredOnce(): void
    {
        $this->remind();
        $reminder = $this->onlyReminder();
        $this->act($reminder, 'open');
        self::assertResponseIsSuccessful();

        $this->act($reminder, 'in10');

        self::assertResponseStatusCodeSame(409);
        self::assertCount(1, $this->reminders());
    }

    public function testAnUnknownOrMissingActionIsRefused(): void
    {
        $this->remind();
        $reminder = $this->onlyReminder();

        $this->act($reminder, 'later');
        self::assertResponseStatusCodeSame(400);
        $this->request('POST', '/api/task-reminders/'.$reminder->getId().'/action', []);
        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->onlyReminder()->getAction());
    }

    public function testAnActionTheKindDoesNotOfferIsRefused(): void
    {
        $this->remind();

        $this->act($this->onlyReminder(), 'tomorrow');

        self::assertResponseStatusCodeSame(409);
        self::assertNull($this->onlyReminder()->getAction());
    }

    public function testAnotherUsersRoutineReminderIsNotFound(): void
    {
        $other = $this->em()->find(User::class, $this->getFixture('other_user')->getId());
        \assert($other instanceof User);
        $reminder = (new TaskReminder())->setUser($other)->setKind(TaskReminder::KIND_ROUTINE)->setSubjectId(self::TODAY.':afterDinner')
            ->setScheduledFor(Clock::get()->now())->setSentAt(Clock::get()->now());
        $this->em()->persist($reminder);
        $this->em()->flush();

        $this->act($reminder, 'open');

        self::assertResponseStatusCodeSame(404);
    }

    public function testAnActionNeedsAUser(): void
    {
        $this->remind();
        $reminder = $this->onlyReminder();

        $this->client->request('POST', '/api/task-reminders/'.$reminder->getId().'/action', [], [], ['CONTENT_TYPE' => 'application/json'], '{"action":"open"}');

        self::assertResponseStatusCodeSame(401);
    }

    private function at(string $instant): void
    {
        Clock::set(new MockClock($instant));
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    private function user(): User
    {
        $user = $this->em()->find(User::class, $this->getFixture('test_user')->getId());
        \assert($user instanceof User);

        return $user;
    }

    /** @return array<string, mixed>|null */
    private function request(string $method, string $endpoint, mixed $body = null): ?array
    {
        $this->client->request($method, $endpoint, [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json', 'HTTP_ACCEPT' => 'application/json'], null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR));

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function act(TaskReminder $reminder, string $action): array
    {
        return $this->request('POST', '/api/task-reminders/'.$reminder->getId().'/action', ['action' => $action]) ?? [];
    }

    /** @param array<string, mixed> $settings */
    private function settings(array $settings): void
    {
        $this->request('PUT', '/api/task-settings/me', $settings);
        self::assertResponseIsSuccessful();
    }

    private function remind(): int
    {
        $sent = self::getContainer()->get(TaskReminders::class)->run($this->user());
        $this->em()->clear();

        return $sent;
    }

    /** A reminder of another kind that went out an hour ago. */
    private function sentEarlierToday(): void
    {
        $hourAgo = Clock::get()->now()->modify('-1 hour');
        $reminder = (new TaskReminder())->setUser($this->user())->setKind(TaskReminder::KIND_NUDGE)->setSubjectId('00000000000000000000000000')
            ->setScheduledFor($hourAgo)->setSentAt($hourAgo);
        $this->em()->persist($reminder);
        $this->em()->flush();
        $this->em()->clear();
    }

    /** @return list<TaskReminder> in the order planned */
    private function reminders(): array
    {
        $this->em()->clear();

        return array_values(array_filter(
            $this->em()->getRepository(TaskReminder::class)->findBy(['user' => $this->getFixture('test_user')->getId()], ['scheduledFor' => 'ASC', 'id' => 'ASC']),
            static fn (TaskReminder $reminder): bool => TaskReminder::KIND_ROUTINE === $reminder->getKind(),
        ));
    }

    private function onlyReminder(): TaskReminder
    {
        $reminders = $this->reminders();
        self::assertCount(1, $reminders);

        return $reminders[0];
    }

    /** @return list<Notification> */
    private function notifications(): array
    {
        $this->em()->clear();

        return $this->em()->getRepository(Notification::class)->findBy(['type' => NotificationType::Routine, 'user' => $this->getFixture('test_user')->getId()]);
    }

    /** @return list<string> the dates ticked */
    private function storedChecks(string $ref): array
    {
        $this->em()->clear();
        $dates = array_map(
            static fn (RoutineCheck $check): string => $check->getDate()->format('Y-m-d'),
            $this->em()->getRepository(RoutineCheck::class)->findBy(['routine' => $this->id($ref)]),
        );
        sort($dates);

        return $dates;
    }

    /**
     * @param list<string> $messages
     *
     * @return list<string>
     */
    private static function bank(array $messages, string $list): array
    {
        return array_map(static fn (string $m): string => str_replace('{liste}', $list, $m), $messages);
    }
}
