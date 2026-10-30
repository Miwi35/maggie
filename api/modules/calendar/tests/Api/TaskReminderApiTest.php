<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\DayPlan;
use Maggie\Calendar\Entity\Reward;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Entity\TaskReminder;
use Maggie\Calendar\Enum\DayMoment;
use Maggie\Calendar\Enum\TaskCriticality;
use Maggie\Calendar\Enum\TaskMoment;
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
use Symfony\Component\Uid\Ulid;

/**
 * Reminders that change their words (MAG-510): a nudge on the task « now »
 * at the moments of the day, at most the daily cap, nothing in the quiet
 * hours, never the same message twice in a row; a task at a precise time
 * reminded at each of its delays (MAG-718), no morning alert anymore — and
 * the buttons that answer them in one gesture.
 *
 * Paris is UTC+2 on 2026-10-14: 06:30 UTC is 08:30, `afterBreakfast`.
 */
final class TaskReminderApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string TODAY = '2026-10-14';
    private const string AFTER_BREAKFAST = '2026-10-14 06:30:00 UTC';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        $this->at(self::AFTER_BREAKFAST);
        $this->loadFixtures('TaskReminderApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testAtAfterBreakfastTheTaskNowOfAConfirmedDayGetsANudgeFromTheBankOfTheToneSet(): void
    {
        $this->setDay(['garagiste', 'courrier']);
        $this->resetMercure();

        self::assertSame(1, $this->remind());

        $reminder = $this->onlyReminder();
        self::assertSame(TaskReminder::KIND_NUDGE, $reminder->getKind());
        self::assertSame(DayMoment::AfterBreakfast, $reminder->getMoment());
        self::assertSame($this->id('garagiste'), $reminder->getSubjectId());
        self::assertFalse($reminder->isSnoozed());
        self::assertNotNull($reminder->getSentAt());
        self::assertStringStartsWith('nudge.doux.', (string) $reminder->getMessageKey());

        $notification = $reminder->getNotification();
        self::assertInstanceOf(Notification::class, $notification);
        self::assertSame(NotificationType::TaskNudge, $notification->getType());
        self::assertContains($notification->getTitle(), self::bank(ReminderMessages::NUDGE['doux'], 'Appeler le garagiste'));
        self::assertSame('Premier pas : Trouver le numéro', $notification->getBody());
        self::assertSame(['start', 'in10', 'tonight'], $notification->getActions());
        self::assertSame((string) $reminder->getId(), $notification->getReminderId());
        self::assertSame('/api/tasks/'.$this->id('garagiste'), $notification->getRelatedEntityIri());
        $this->assertMercureUpdatePublished('/api/notifications/');
        $this->assertElasticsearchIndexDispatched(Notification::class);

        $this->at('2026-10-14 06:31:00 UTC');
        self::assertSame(0, $this->remind(), 'once per moment and per day');
        self::assertCount(1, $this->reminders());
    }

    public function testTheToneSetDecidesTheBank(): void
    {
        $this->settings(['tone' => 'drole']);
        $this->setDay(['garagiste']);

        $this->remind();

        self::assertStringStartsWith('nudge.drole.', (string) $this->onlyReminder()->getMessageKey());
        self::assertContains($this->onlyReminder()->getNotification()?->getTitle(), self::bank(ReminderMessages::NUDGE['drole'], 'Appeler le garagiste'));
    }

    public function testADayNotConfirmedOrWhoseFiveAreDoneGetsNoNudge(): void
    {
        self::assertSame(0, $this->remind(), 'no day at all');

        $this->setDay(['garagiste'], confirm: false);
        self::assertSame(0, $this->remind(), 'a day only proposed');

        $this->setDay(['garagiste']);
        $this->em()->find(Task::class, $this->getFixture('garagiste')->getId())?->setCompletedAt(new \DateTimeImmutable('2026-10-14 06:00 UTC'));
        $this->em()->flush();
        $this->at('2026-10-14 06:32:00 UTC');
        self::assertSame(0, $this->remind(), 'nothing left « now »');
    }

    public function testTheMomentsAreTheUsers(): void
    {
        $this->settings(['dayMoments' => ['afterBreakfast' => '09:15']]);
        $this->setDay(['garagiste']);

        self::assertSame(0, $this->remind(), '08:30 is no longer a moment');
        $this->at('2026-10-14 07:15:00 UTC');
        self::assertSame(1, $this->remind(), '09:15 is');
    }

    public function testAfterLunchSendsAnotherNudgeNeverWithTheSameMessage(): void
    {
        $this->setDay(['garagiste']);
        $this->remind();
        $this->at('2026-10-14 11:30:00 UTC');
        $this->remind();

        $reminders = $this->reminders();
        self::assertCount(2, $reminders);
        self::assertSame(DayMoment::AfterLunch, $reminders[1]->getMoment());
        self::assertNotSame($reminders[0]->getMessageKey(), $reminders[1]->getMessageKey());
        self::assertNotSame($reminders[0]->getNotification()?->getTitle(), $reminders[1]->getNotification()?->getTitle());
    }

    public function testNoMorningAlertGoesOutAnymoreTheDigestTellsThem(): void
    {
        $this->dueTasks(9);
        $this->at('2026-10-14 06:00:00 UTC');

        self::assertSame(0, $this->remind(), '08:00, the morning of old');

        self::assertSame([], $this->notifications(NotificationType::TaskDue));
        self::assertSame([], $this->reminders());
    }

    public function testATaskAt1800WithAReminder30MinutesBeforeIsRemindedAt1730(): void
    {
        $task = $this->timedTask('Préparer le dîner', '18:00', [30]);

        $this->at('2026-10-14 15:29:00 UTC');
        self::assertSame(0, $this->remind(), '17:29, not yet');

        $this->at('2026-10-14 15:30:00 UTC');
        self::assertSame(1, $this->remind(), '17:30');

        $reminder = $this->onlyReminder();
        self::assertSame(TaskReminder::KIND_DUE, $reminder->getKind());
        self::assertSame((string) $task->getId(), $reminder->getSubjectId());
        self::assertSame('2026-10-14T17:30:00+02:00', $reminder->getScheduledFor()->setTimezone(new \DateTimeZone('Europe/Paris'))->format('c'));
        $notification = $reminder->getNotification();
        self::assertInstanceOf(Notification::class, $notification);
        self::assertSame(NotificationType::TaskDue, $notification->getType());
        self::assertSame('Préparer le dîner', $notification->getTitle());
        self::assertSame('Dans 30 min · 18:00', $notification->getBody());
        self::assertSame(['start', 'done', 'tomorrow'], $notification->getActions());
        self::assertSame('/api/tasks/'.$task->getId(), $notification->getRelatedEntityIri());

        $this->at('2026-10-14 15:31:00 UTC');
        self::assertSame(0, $this->remind(), 'once per reminder');
    }

    public function testEachReminderOfATaskGoesOutAtItsMinuteWhateverTheCap(): void
    {
        $this->settings(['reminderDailyCap' => 1]);
        $this->timedTask('Appeler maman', '18:00', [0, 60]);

        $this->at('2026-10-14 15:00:00 UTC');
        self::assertSame(1, $this->remind(), '17:00, an hour before');
        $this->at('2026-10-14 16:00:00 UTC');
        self::assertSame(1, $this->remind(), '18:00, at the time: set by the user, the cap of 1 does not hold it back');

        $bodies = array_map(static fn (TaskReminder $r): ?string => $r->getNotification()?->getBody(), $this->reminders());
        self::assertSame(['Dans 1 h · 18:00', 'C\'est l\'heure · 18:00'], $bodies);
    }

    public function testATaskDoneOrWithoutAPreciseTimeIsNotReminded(): void
    {
        $done = $this->timedTask('Déjà fait', '18:00', [0]);
        $this->em()->find(Task::class, $done->getId())?->setCompletedAt(new \DateTimeImmutable('2026-10-14 10:00 UTC'));
        $evening = (new Task())->setUser($this->user())->setTitle('Ce soir')->schedule(new \DateTimeImmutable(self::TODAY), TaskMoment::Evening, null, []);
        $this->em()->persist($evening);
        $this->em()->flush();

        $this->at('2026-10-14 16:00:00 UTC');

        self::assertSame(0, $this->remind());
    }

    public function testAReminderInTheQuietHoursIsDropped(): void
    {
        $this->timedTask('Sortir les poubelles', '21:30', [0]);

        $this->at('2026-10-14 19:30:00 UTC');

        self::assertSame(0, $this->remind(), '21:30 is quiet');
        self::assertNotNull($this->onlyReminder()->getDroppedAt());
    }

    public function testIn10PutsTheNudgeOffTenMinutesOutsideTheCap(): void
    {
        $this->settings(['reminderDailyCap' => 1]);
        $reminder = $this->sentNudge();
        $notificationId = (string) $reminder->getNotification()?->getId();
        $this->resetMercure();

        $this->at('2026-10-14 06:31:00 UTC');
        $response = $this->act($reminder, 'in10');

        self::assertResponseIsSuccessful();
        self::assertSame('in10', $response['reminder']['action']);
        self::assertTrue($response['next']['snoozed']);
        self::assertSame('2026-10-14T08:41:00+02:00', $response['next']['scheduledFor']);
        self::assertNull($response['notification'], 'it goes away');
        self::assertNull($this->em()->find(Notification::class, $notificationId));
        $this->assertMercureDeletePublished('/api/notifications/'.$notificationId);
        $this->assertElasticsearchDeleteDispatched();

        $this->at('2026-10-14 06:40:00 UTC');
        self::assertSame(0, $this->remind(), 'not before 08:41');
        $this->at('2026-10-14 06:41:00 UTC');
        self::assertSame(1, $this->remind(), 'the cap of 1 is spent, yet a reminder put off comes back');

        $back = $this->reminders()[1];
        self::assertTrue($back->isSnoozed());
        self::assertNotNull($back->getSentAt());
        self::assertNotSame($reminder->getMessageKey(), $back->getMessageKey());
        self::assertSame(NotificationType::TaskNudge, $back->getNotification()?->getType());
    }

    public function testNothingGoesOutInTheQuietHoursAndAnIn10AskedAt2055IsDropped(): void
    {
        $this->at('2026-10-14 18:55:00 UTC');
        $reminder = $this->sentNudge();
        $this->act($reminder, 'in10');
        self::assertResponseIsSuccessful();

        $this->at('2026-10-14 19:05:00 UTC');
        self::assertSame(0, $this->remind(), '21:05 is quiet');
        $this->at('2026-10-14 19:30:00 UTC');
        self::assertSame(0, $this->remind(), '21:30 neither');

        $snoozed = $this->reminders()[1];
        self::assertNull($snoozed->getSentAt());
        self::assertNotNull($snoozed->getDroppedAt(), 'dropped, not piled up for the morning');

        $this->at('2026-10-15 06:00:00 UTC');
        self::assertSame(0, $this->remind(), 'the morning after sends nothing of it');
        self::assertSame([], $this->notifications(NotificationType::TaskNudge), 'the one put off went away and never came back');
    }

    public function testTonightPutsTheNudgeOffToAfterDinner(): void
    {
        $reminder = $this->sentNudge();

        $response = $this->act($reminder, 'tonight');

        self::assertResponseIsSuccessful();
        self::assertSame('2026-10-14T20:00:00+02:00', $response['next']['scheduledFor']);
        self::assertSame('afterDinner', $response['next']['moment']);
        self::assertTrue($response['next']['snoozed']);
        self::assertNull($response['notification']);
    }

    public function testAfterDinnerTonightIsNotOfferedTomorrowIs(): void
    {
        $this->settings(['dayMoments' => ['afterDinner' => '12:00']]);
        $this->setDay(['garagiste']);
        $this->at('2026-10-14 11:30:00 UTC');

        $this->remind();

        self::assertSame(['start', 'in10', 'tomorrow'], $this->onlyReminder()->getNotification()?->getActions());

        $this->act($this->onlyReminder(), 'tonight');
        self::assertResponseStatusCodeSame(409);
        self::assertNull($this->onlyReminder()->getAction(), 'refused, the reminder can still be answered');
    }

    public function testDoneTicksTheTaskAndTheNotificationSaysTheBravo(): void
    {
        $reminder = $this->sentDue();
        $notificationId = (string) $reminder->getNotification()?->getId();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $response = $this->act($reminder, 'done');

        self::assertResponseIsSuccessful();
        $task = $this->em()->find(Task::class, Ulid::fromString($reminder->getSubjectId()));
        self::assertNotNull($task?->getCompletedAt());
        $reward = $this->em()->getRepository(Reward::class)->findOneBy(['subjectId' => $reminder->getSubjectId()]);
        self::assertInstanceOf(Reward::class, $reward);
        self::assertSame($reward->getMessage(), $response['reward']['message']);

        $notification = $this->em()->find(Notification::class, $notificationId);
        self::assertInstanceOf(Notification::class, $notification);
        self::assertSame($reward->getMessage(), $notification->getTitle());
        self::assertSame([], $notification->getActions());
        self::assertNotNull($notification->getReadAt());
        self::assertSame($reward->getMessage(), $response['notification']['title']);
        $this->assertMercureUpdatePublished('/api/notifications/'.$notificationId);
        $this->assertElasticsearchIndexDispatchedFor(Notification::class, $notificationId);
    }

    public function testEachKindIsAnsweredWithItsOwnButtons(): void
    {
        $due = $this->sentDue();
        $this->act($due, 'in10');
        self::assertResponseStatusCodeSame(409, 'a reminder at a precise time is not put off');
        $this->act($due, 'tonight');
        self::assertResponseStatusCodeSame(409);

        self::assertNull($this->onlyReminder()->getAction(), 'refused, it can still be answered');
        self::assertCount(1, $this->reminders());
    }

    public function testTomorrowOnAReminderAtAPreciseTimeMovesItsDayKeepingItsTime(): void
    {
        $reminder = $this->sentDue();
        $time = $this->em()->find(Task::class, Ulid::fromString($reminder->getSubjectId()))?->getScheduledTime();
        $this->resetMercure();

        $this->act($reminder, 'tomorrow');

        self::assertResponseIsSuccessful();
        $task = $this->em()->find(Task::class, Ulid::fromString($reminder->getSubjectId()));
        self::assertSame('2026-10-15', $task?->getScheduledDate()?->format('Y-m-d'));
        self::assertSame(TaskMoment::Time, $task->getScheduledMoment());
        self::assertSame($time, $task->getScheduledTime());
        self::assertSame([0], $task->getReminders());
        $this->assertMercureUpdatePublished('/tasks/'.$reminder->getSubjectId());
        self::assertNull($this->em()->getRepository(DayPlan::class)->findOneBy(['user' => $this->getFixture('test_user')->getId(), 'date' => new \DateTimeImmutable('2026-10-15')]), 'its day is its programmation, not tomorrow\'s five');
    }

    public function testTomorrowOnAReminderDaysAheadPutsTheTaskOffADayFromItsOwnDay(): void
    {
        $now = Clock::get()->now()->setTimezone(new \DateTimeZone('Europe/Paris'))->format('H:i');
        $task = $this->timedTask('Vol pour Lyon', $now, [2880], '2026-10-16');
        self::assertSame(1, $this->remind(), 'two days before');

        $this->act($this->onlyReminder(), 'tomorrow');

        self::assertResponseIsSuccessful();
        self::assertSame('2026-10-17', $this->em()->find(Task::class, $task->getId())?->getScheduledDate()?->format('Y-m-d'));
    }

    public function testANudgeIsNotTicked(): void
    {
        $this->act($this->sentNudge(), 'done');

        self::assertResponseStatusCodeSame(409);
        self::assertNull($this->em()->find(Task::class, $this->getFixture('garagiste')->getId())?->getCompletedAt());
    }

    public function testTomorrowPutsTheTaskInTomorrowsFiveAsAProposal(): void
    {
        $reminder = $this->sentNudge();

        $this->act($reminder, 'tomorrow');

        self::assertResponseIsSuccessful();
        $plan = $this->em()->getRepository(DayPlan::class)->findOneBy(['user' => $this->getFixture('test_user')->getId(), 'date' => new \DateTimeImmutable('2026-10-15')]);
        self::assertInstanceOf(DayPlan::class, $plan);
        self::assertSame([$this->id('garagiste')], $plan->getTaskIds());
        self::assertNull($plan->getConfirmedAt(), 'tomorrow stays a proposal');
        $today = $this->em()->getRepository(DayPlan::class)->findOneBy(['user' => $this->getFixture('test_user')->getId(), 'date' => new \DateTimeImmutable(self::TODAY)]);
        self::assertSame([], $today?->getTaskIds(), 'it leaves today\'s five, or it would be nudged again after lunch');
        self::assertNotNull($today->getConfirmedAt());
        $notification = $this->onlyReminder()->getNotification();
        self::assertNotNull($notification?->getReadAt());
        self::assertSame([], $notification?->getActions());
    }

    public function testTomorrowRefusesAFullDay(): void
    {
        $reminder = $this->sentNudge();
        $ids = [];
        foreach (range(1, 5) as $n) {
            $ids[] = (string) $this->task('Demain '.$n)->getId();
        }
        $this->request('PUT', '/api/day-plans/2026-10-15', ['slots' => array_map(static fn (string $id): array => ['type' => 'task', 'taskId' => $id], $ids)]);
        self::assertResponseIsSuccessful();

        $this->act($reminder, 'tomorrow');

        self::assertResponseStatusCodeSame(409);
    }

    public function testAReminderIsAnsweredOnce(): void
    {
        $reminder = $this->sentNudge();
        $this->act($reminder, 'start');
        self::assertResponseIsSuccessful();
        self::assertNotNull($this->onlyReminder()->getNotification()?->getReadAt());

        $this->act($reminder, 'in10');

        self::assertResponseStatusCodeSame(409);
        self::assertCount(1, $this->reminders());
    }

    public function testAnUnknownOrMissingActionIsRefused(): void
    {
        $reminder = $this->sentNudge();

        $this->act($reminder, 'later');
        self::assertResponseStatusCodeSame(400);
        $this->request('POST', '/api/task-reminders/'.$reminder->getId().'/action', []);
        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->onlyReminder()->getAction());
    }

    public function testAnotherUsersReminderIsNotFound(): void
    {
        $other = $this->em()->find(User::class, $this->getFixture('other_user')->getId());
        \assert($other instanceof User);
        $reminder = (new TaskReminder())->setUser($other)->setKind(TaskReminder::KIND_NUDGE)->setSubjectId($this->id('other_task'))
            ->setScheduledFor(Clock::get()->now())->setSentAt(Clock::get()->now());
        $this->em()->persist($reminder);
        $this->em()->flush();

        $this->act($reminder, 'done');
        self::assertResponseStatusCodeSame(404);
        $this->request('POST', '/api/task-reminders/not-an-id/action', ['action' => 'done']);
        self::assertResponseStatusCodeSame(404);
        self::assertNull($this->em()->find(Task::class, $this->getFixture('other_task')->getId())?->getCompletedAt());
    }

    public function testAnActionNeedsAUser(): void
    {
        $reminder = $this->sentNudge();

        $this->client->request('POST', '/api/task-reminders/'.$reminder->getId().'/action', [], [], ['CONTENT_TYPE' => 'application/json'], '{"action":"done"}');

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

    /** @param list<string> $refs */
    private function setDay(array $refs, bool $confirm = true): void
    {
        $this->request('PUT', '/api/day-plans/'.self::TODAY, [
            'slots' => array_map(fn (string $ref): array => ['type' => 'task', 'taskId' => $this->id($ref)], $refs),
            'confirm' => $confirm,
        ]);
        self::assertResponseIsSuccessful();
    }

    private function remind(): int
    {
        $sent = self::getContainer()->get(TaskReminders::class)->run($this->user());
        $this->em()->clear();

        return $sent;
    }

    /** A nudge on « Appeler le garagiste », sent now. */
    private function sentNudge(): TaskReminder
    {
        $this->settings(['dayMoments' => ['afterBreakfast' => Clock::get()->now()->setTimezone(new \DateTimeZone('Europe/Paris'))->format('H:i')]]);
        $this->setDay(['garagiste']);
        self::assertSame(1, $this->remind());

        return $this->onlyReminder();
    }

    /** A reminder at the time of « Échéance 1 », planned now, sent now. */
    private function sentDue(): TaskReminder
    {
        $this->timedTask('Échéance 1', Clock::get()->now()->setTimezone(new \DateTimeZone('Europe/Paris'))->format('H:i'), [0]);
        self::assertSame(1, $this->remind());

        return $this->onlyReminder();
    }

    /** @param list<int> $reminders */
    private function timedTask(string $title, string $time, array $reminders, string $day = self::TODAY): Task
    {
        $task = (new Task())->setUser($this->user())->setTitle($title)->setCriticality(TaskCriticality::Medium)
            ->schedule(new \DateTimeImmutable($day), TaskMoment::Time, $time, $reminders);
        $this->em()->persist($task);
        $this->em()->flush();
        $this->em()->clear();

        return $task;
    }

    private function task(string $title, ?string $due = null): Task
    {
        $task = (new Task())->setUser($this->user())->setTitle($title)->setCriticality(TaskCriticality::Medium)
            ->setDueDate(null === $due ? null : new \DateTimeImmutable($due, new \DateTimeZone('UTC')));
        $this->em()->persist($task);
        $this->em()->flush();

        return $task;
    }

    private function dueTasks(int $count): void
    {
        foreach (range(1, $count) as $n) {
            $this->task('Échéance '.$n, self::TODAY);
        }
        $this->em()->clear();
    }

    /** @return list<TaskReminder> in the order planned */
    private function reminders(): array
    {
        $this->em()->clear();

        return $this->em()->getRepository(TaskReminder::class)->findBy(['user' => $this->getFixture('test_user')->getId()], ['scheduledFor' => 'ASC', 'id' => 'ASC']);
    }

    private function onlyReminder(): TaskReminder
    {
        $reminders = $this->reminders();
        self::assertCount(1, $reminders);

        return $reminders[0];
    }

    /** @return list<Notification> */
    private function notifications(NotificationType $type): array
    {
        return $this->em()->getRepository(Notification::class)->findBy(['type' => $type, 'user' => $this->getFixture('test_user')->getId()]);
    }

    /**
     * @param list<string> $messages
     *
     * @return list<string>
     */
    private static function bank(array $messages, string $title, int $days = 0): array
    {
        return array_map(static fn (string $m): string => str_replace(['{titre}', '{jours}'], [$title, (string) $days], $m), $messages);
    }
}
