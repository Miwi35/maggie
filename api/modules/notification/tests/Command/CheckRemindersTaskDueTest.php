<?php

declare(strict_types=1);

namespace Maggie\Notification\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\DayPlan;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Entity\TaskReminder;
use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Calendar\Enum\TaskCriticality;
use Maggie\Calendar\Enum\TaskPriority;
use Maggie\Calendar\Service\ReminderMessages;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserPreference;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Message\SendPushNotificationCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;
use Symfony\Component\Console\Application;
use Symfony\Component\Console\Tester\CommandTester;
use Symfony\Component\Uid\Ulid;

/**
 * « Tâche due » (MAG-410): on the user's morning, `maggie:notification:check-reminders`
 * files one `task_due` notification per task the alert schedule picks
 * ({@see \Maggie\Calendar\Service\TaskAlertSchedule}), once per task and per day,
 * in words drawn from a bank (MAG-510).
 *
 * The clock is pinned: 2026-10-13 06:00 UTC is 08:00 in Paris (CEST), the
 * default morning of the default timezone.
 */
final class CheckRemindersTaskDueTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    private const MORNING = '2026-10-13 06:00:00 UTC';

    private CommandTester $tester;
    private MockClock $clock;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures();
        $this->at(self::MORNING);

        $application = new Application();
        $application->add(self::getContainer()->get(\Maggie\Notification\Command\CheckRemindersCommand::class));
        $this->tester = new CommandTester($application->find('maggie:notification:check-reminders'));
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testEachTaskTheScheduleAlertsGetsOneNotificationWithItsReason(): void
    {
        $user = $this->user('alice@example.com');
        $this->task($user, 'Déclarer les impôts', TaskCriticality::Critical, '+3 days');
        $this->task($user, 'Payer la cantine', TaskCriticality::Medium, '+0 days');
        $this->task($user, 'Rendre le livre', TaskCriticality::High, '-2 days');
        $this->flushAndReset();

        $this->runCron();

        self::assertSame(
            ['daysBefore · Déclarer les impôts', 'dueDay · Payer la cantine', 'overdue · Rendre le livre'],
            $this->titles(),
        );
        $notification = $this->notificationOn('Déclarer les impôts');
        self::assertSame(NotificationType::TaskDue, $notification->getType());
        // Reworded from the bank (MAG-510): the days are in the words, never « En retard ».
        self::assertContains($notification->getTitle(), array_map(
            static fn (string $m): string => str_replace(['{titre}', '{jours}'], ['Déclarer les impôts', '3'], $m),
            ReminderMessages::DUE['daysBefore'][TaskSettings::DEFAULT_TONE],
        ));
        self::assertSame(['start', 'done', 'tomorrow'], $notification->getActions());
        self::assertNotNull($notification->getReminderId());
        self::assertNull($notification->getReadAt());
        self::assertSame($user->getId()->toRfc4122(), $notification->getUser()->getId()->toRfc4122());
        foreach ($this->em()->getRepository(Notification::class)->findAll() as $sent) {
            self::assertStringNotContainsStringIgnoringCase('retard', $sent->getTitle());
        }

        $this->assertMercureUpdatePublished('/api/notifications/');
        $this->assertElasticsearchIndexDispatched(Notification::class);
        self::assertSame(3, $this->pushesRequested(), 'one push per notification');
    }

    public function testLowDoneUndatedAndFarTasksAreNotAlerted(): void
    {
        $user = $this->user('alice@example.com');
        $this->task($user, 'Basse, dans deux jours', TaskCriticality::Low, '+2 days');
        $this->task($user, 'Loin', TaskCriticality::Critical, '+10 days');
        $this->task($user, 'Sans date', TaskCriticality::Critical, null);
        $this->task($user, 'Faite', TaskCriticality::Critical, '+0 days')->setCompletedAt(new \DateTimeImmutable());
        $this->flushAndReset();

        $this->runCron();

        self::assertSame([], $this->titles());
        $this->assertMercureUpdateCount(0);
        self::assertSame(0, $this->pushesRequested());
    }

    public function testASecondRunTheMinuteAfterCreatesNothingMore(): void
    {
        $user = $this->user('alice@example.com');
        $this->task($user, 'Payer la cantine', TaskCriticality::Medium, '+0 days');
        $this->flushAndReset();

        $this->runCron();
        $this->at('2026-10-13 06:01:00 UTC');
        $this->runCron();

        self::assertSame(['dueDay · Payer la cantine'], $this->titles());
        self::assertSame(1, $this->pushesRequested());
    }

    public function testTheNextMorningAlertsAgainATaskStillLate(): void
    {
        $user = $this->user('alice@example.com');
        $this->task($user, 'Rendre le livre', TaskCriticality::High, '-2 days');
        $this->flushAndReset();

        $this->runCron();
        $this->at('2026-10-14 06:00:00 UTC');
        $this->runCron();

        self::assertSame(['overdue · Rendre le livre', 'overdue · Rendre le livre'], $this->titles());
    }

    public function testNothingFiresOutsideTheMorning(): void
    {
        $user = $this->user('alice@example.com');
        $this->task($user, 'Payer la cantine', TaskCriticality::Medium, '+0 days');
        $this->flushAndReset();

        $this->at('2026-10-13 05:59:00 UTC');
        $this->runCron();
        $this->at('2026-10-13 09:00:00 UTC');
        $this->runCron();

        self::assertSame([], $this->titles());
    }

    public function testAMissedMinuteStillAlertsOnceWithinTheGracePeriod(): void
    {
        $user = $this->user('alice@example.com');
        $this->task($user, 'Payer la cantine', TaskCriticality::Medium, '+0 days');
        $this->flushAndReset();

        $this->at('2026-10-13 06:09:00 UTC');
        $this->runCron();
        $this->at('2026-10-13 06:10:00 UTC');
        $this->runCron();

        self::assertSame(['dueDay · Payer la cantine'], $this->titles());
    }

    public function testTheGracePeriodEndsTenMinutesAfterTheMorning(): void
    {
        $user = $this->user('alice@example.com');
        $this->task($user, 'Payer la cantine', TaskCriticality::Medium, '+0 days');
        $this->flushAndReset();

        $this->at('2026-10-13 06:10:00 UTC');
        $this->runCron();

        self::assertSame([], $this->titles());
    }

    public function testAUserWhoChoseHalfPastNineIsAlertedAtHalfPastNineInTheirOwnZone(): void
    {
        $late = $this->user('late@example.com');
        $this->settings($late, '09:30');
        $this->task($late, 'Tâche tardive', TaskCriticality::Medium, '+0 days');
        $default = $this->user('alice@example.com');
        $this->task($default, 'Tâche de 8 h', TaskCriticality::Medium, '+0 days');
        $this->flushAndReset();

        $this->at(self::MORNING);
        $this->runCron();
        self::assertSame(['dueDay · Tâche de 8 h'], $this->titles(), '08:00 in Paris: the default morning only');

        $this->at('2026-10-13 07:30:00 UTC');
        $this->runCron();
        self::assertSame(['dueDay · Tâche de 8 h', 'dueDay · Tâche tardive'], $this->titles(), '09:30 in Paris: the late one too');
    }

    public function testAMorningInTheQuietHoursIsAlertedWhenTheyEnd(): void
    {
        $early = $this->user('early@example.com');
        $this->settings($early, '07:30');
        $this->task($early, 'Tâche matinale', TaskCriticality::Medium, '+0 days');
        $this->flushAndReset();

        $this->at('2026-10-13 05:30:00 UTC');
        $this->runCron();
        self::assertSame([], $this->titles(), '07:30 in Paris is quiet until 08:00');

        $this->at(self::MORNING);
        $this->runCron();
        self::assertSame(['dueDay · Tâche matinale'], $this->titles(), 'max(morning, end of the quiet hours)');
    }

    public function testTheMorningIsThatOfTheUsersTimezone(): void
    {
        $user = $this->user('newyork@example.com');
        $this->timezone($user, 'America/New_York');
        $this->task($user, 'Appeler maman', TaskCriticality::Medium, '+0 days');
        $this->flushAndReset();

        $this->at(self::MORNING);
        $this->runCron();
        self::assertSame([], $this->titles(), '08:00 in Paris is 02:00 in New York');

        $this->at('2026-10-13 12:00:00 UTC');
        $this->runCron();
        self::assertSame(['dueDay · Appeler maman'], $this->titles(), '08:00 in New York (EDT)');
    }

    public function testATaskIsDueOnTheDayOfItsOwner(): void
    {
        $user = $this->user('auckland@example.com');
        $this->timezone($user, 'Pacific/Auckland');
        // 08:00 on the 13th in Auckland (NZDT) is still the 12th for the server in UTC.
        $this->task($user, 'Due là-bas aujourd\'hui', TaskCriticality::Medium, '+0 days');
        $this->flushAndReset();

        $this->at('2026-10-12 19:00:00 UTC');
        $this->runCron();

        self::assertSame(['dueDay · Due là-bas aujourd\'hui'], $this->titles());
    }

    public function testEachUserIsAlertedOnTheirOwnTasksOnly(): void
    {
        $this->task($this->user('alice@example.com'), 'De Alice', TaskCriticality::Medium, '+0 days');
        $this->task($this->user('bob@example.com'), 'De Bob', TaskCriticality::Medium, '+0 days');
        $this->flushAndReset();

        $this->runCron();

        self::assertSame(['dueDay · De Alice'], $this->titlesOf('alice@example.com'));
        self::assertSame(['dueDay · De Bob'], $this->titlesOf('bob@example.com'));
    }

    public function testNothingIsCreatedForAUserWhoTurnedNotificationsOff(): void
    {
        $user = $this->user('silent@example.com');
        $preference = (new UserPreference())->setUser($user)->setNotificationsEnabled(false);
        $this->em()->persist($preference);
        $this->task($user, 'Payer la cantine', TaskCriticality::Medium, '+0 days');
        $this->flushAndReset();

        $this->runCron();

        self::assertSame([], $this->titles());
        self::assertSame(0, $this->pushesRequested());
    }

    public function testTheUsersAlertScheduleDecidesWhatIsAlerted(): void
    {
        $user = $this->user('alice@example.com');
        $this->em()->persist((new TaskSettings())->setUser($user)->setAlertSchedule(
            \Maggie\Calendar\Service\TaskAlertSchedule::merge(
                \Maggie\Calendar\Service\TaskAlertSchedule::DEFAULTS,
                ['critical' => ['daysBefore' => [], 'dueDay' => false, 'overdueEveryDays' => null]],
            ),
        ));
        $this->task($user, 'Silencieuse', TaskCriticality::Critical, '+0 days');
        $this->task($user, 'Bavarde', TaskCriticality::Medium, '+0 days');
        $this->flushAndReset();

        $this->runCron();

        self::assertSame(['dueDay · Bavarde'], $this->titles());
    }

    public function testAtAfterBreakfastTheCronNudgesTheTaskNowOfAConfirmedDayOnce(): void
    {
        $user = $this->user('alice@example.com');
        $task = $this->task($user, 'Appeler le garagiste', TaskCriticality::Medium, null);
        $this->em()->persist((new DayPlan())->setUser($user)->setDate(new \DateTimeImmutable('2026-10-13', new \DateTimeZone('UTC')))
            ->setSlots([['type' => DayPlan::TYPE_TASK, 'taskId' => (string) $task->getId()]])->setConfirmedAt(new \DateTimeImmutable('2026-10-13 05:00 UTC')));
        $this->flushAndReset();

        $this->at('2026-10-13 06:30:00 UTC');
        $this->runCron();
        $this->at('2026-10-13 06:31:00 UTC');
        $this->runCron();

        $nudges = $this->em()->getRepository(Notification::class)->findBy(['type' => NotificationType::TaskNudge]);
        self::assertCount(1, $nudges, 'once per moment and per day');
        self::assertStringContainsString('« Appeler le garagiste »', $nudges[0]->getTitle());
        self::assertSame(['start', 'in10', 'tonight'], $nudges[0]->getActions());
        self::assertSame(1, $this->pushesRequested());
    }

    private function runCron(): void
    {
        $this->resetMercure();
        $this->tester->execute([]);
        $this->tester->assertCommandIsSuccessful();
        $this->em()->clear();
    }

    private function at(string $instant): void
    {
        $this->clock = new MockClock($instant);
        Clock::set($this->clock);
    }

    private function user(string $email): User
    {
        $existing = $this->em()->getRepository(User::class)->findOneBy(['email' => $email]);
        if ($existing instanceof User) {
            return $existing;
        }

        $user = (new User())->setEmail($email)->setGoogleId('google-'.$email)->setName($email);
        $this->em()->persist($user);

        return $user;
    }

    private function timezone(User $user, string $timezone): void
    {
        $this->em()->persist((new UserPreference())->setUser($user)->setTimezone($timezone));
    }

    private function settings(User $user, string $morningTime): void
    {
        $this->em()->persist((new TaskSettings())->setUser($user)->setMorningTime($morningTime));
    }

    /** @param string|null $due a relative day to the pinned morning, in the Paris calendar */
    private function task(User $user, string $title, TaskCriticality $criticality, ?string $due): Task
    {
        $task = (new Task())
            ->setUser($user)
            ->setTitle($title)
            ->setCriticality($criticality)
            ->setPriority(TaskPriority::Medium)
            ->setDueDate(null === $due ? null : (new \DateTimeImmutable('2026-10-13', new \DateTimeZone('UTC')))->modify($due));
        $this->em()->persist($task);

        return $task;
    }

    private function flushAndReset(): void
    {
        $this->em()->flush();
        $this->em()->clear();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function taskId(string $title): string
    {
        $task = $this->em()->getRepository(Task::class)->findOneBy(['title' => $title]);
        self::assertInstanceOf(Task::class, $task);

        return (string) $task->getId();
    }

    /** @return list<string> */
    private function titles(): array
    {
        return $this->titlesOf(null);
    }

    /**
     * The alerts sent, as « reason · task »: the words are drawn from a bank,
     * the reason is the bank of the reminder's message key.
     *
     * @return list<string>
     */
    private function titlesOf(?string $email): array
    {
        $criteria = ['type' => NotificationType::TaskDue];
        if (null !== $email) {
            $criteria['user'] = $this->em()->getRepository(User::class)->findOneBy(['email' => $email]);
        }

        $titles = [];
        foreach ($this->em()->getRepository(Notification::class)->findBy($criteria) as $notification) {
            $reminder = $this->em()->getRepository(TaskReminder::class)->findOneBy(['notification' => $notification]);
            self::assertInstanceOf(TaskReminder::class, $reminder);
            $task = $this->em()->find(Task::class, Ulid::fromString($reminder->getSubjectId()));
            self::assertInstanceOf(Task::class, $task);
            self::assertStringContainsString('« '.$task->getTitle().' »', $notification->getTitle());
            $titles[] = explode('.', (string) $reminder->getMessageKey())[1].' · '.$task->getTitle();
        }
        sort($titles);

        return $titles;
    }

    private function notificationOn(string $taskTitle): Notification
    {
        $notification = $this->em()->getRepository(Notification::class)->findOneBy(['relatedEntityIri' => '/api/tasks/'.$this->taskId($taskTitle)]);
        self::assertInstanceOf(Notification::class, $notification);

        return $notification;
    }

    private function pushesRequested(): int
    {
        return \count(array_filter(
            $this->getAsyncTransport()->getSent(),
            static fn ($envelope): bool => $envelope->getMessage() instanceof SendPushNotificationCommand,
        ));
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
