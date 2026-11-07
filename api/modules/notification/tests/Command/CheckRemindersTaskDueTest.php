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
use Maggie\Calendar\Enum\TaskCriticality;
use Maggie\Calendar\Enum\TaskMoment;
use Maggie\Calendar\Enum\TaskPriority;
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
 * « Tâche due » (MAG-410, MAG-718): `maggie:notification:check-reminders` files
 * one `task_due` notification at each reminder of a task planned at a precise
 * time, minutes before it as an event — and nothing on a morning anymore: the
 * morning alerts of {@see \Maggie\Calendar\Service\TaskAlertSchedule} are the
 * digest's to tell.
 *
 * The clock is pinned: 2026-10-13 06:00 UTC is 08:00 in Paris (CEST), the
 * morning of old of the default timezone.
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

    public function testNoMorningAlertGoesOutAnymore(): void
    {
        $user = $this->user('alice@example.com');
        $this->task($user, 'Déclarer les impôts', TaskCriticality::Critical, '+3 days');
        $this->task($user, 'Payer la cantine', TaskCriticality::Medium, '+0 days');
        $this->task($user, 'Rendre le livre', TaskCriticality::High, '-2 days');
        $this->flushAndReset();

        $this->runCron();

        self::assertSame([], $this->notifications());
        self::assertSame(0, $this->pushesRequested());
    }

    public function testATaskAtAPreciseTimeIsRemindedAtEachDelay(): void
    {
        $user = $this->user('alice@example.com');
        $this->timed($user, 'Rendez-vous kiné', '+0 days', '10:00', [30]);
        $this->flushAndReset();

        $this->at('2026-10-13 07:29:00 UTC');
        $this->runCron();
        self::assertSame([], $this->notifications(), '09:29 in Paris');

        $this->at('2026-10-13 07:30:00 UTC');
        $this->runCron();

        $notification = $this->notificationOn('Rendez-vous kiné');
        self::assertSame(NotificationType::TaskDue, $notification->getType());
        self::assertSame('Rendez-vous kiné', $notification->getTitle());
        self::assertSame('Dans 30 min · 10:00', $notification->getBody());
        self::assertSame(['start', 'done', 'tomorrow'], $notification->getActions());
        self::assertNotNull($notification->getReminderId());
        self::assertSame($user->getId()->toRfc4122(), $notification->getUser()->getId()->toRfc4122());
        $this->assertMercureUpdatePublished('/api/notifications/');
        $this->assertElasticsearchIndexDispatched(Notification::class);
        self::assertSame(1, $this->pushesRequested(), 'one push per notification');
    }

    public function testASecondRunTheMinuteAfterCreatesNothingMore(): void
    {
        $user = $this->user('alice@example.com');
        $this->timed($user, 'Rendez-vous kiné', '+0 days', '10:00', [0]);
        $this->flushAndReset();

        $this->at('2026-10-13 08:00:00 UTC');
        $this->runCron();
        $this->at('2026-10-13 08:01:00 UTC');
        $this->runCron();

        self::assertSame(['Rendez-vous kiné'], $this->titles());
        self::assertSame(1, $this->pushesRequested());
    }

    public function testAMissedMinuteStillRemindsOnceWithinTheGracePeriod(): void
    {
        $user = $this->user('alice@example.com');
        $this->timed($user, 'Rendez-vous kiné', '+0 days', '10:00', [0]);
        $this->flushAndReset();

        $this->at('2026-10-13 08:09:00 UTC');
        $this->runCron();
        $this->at('2026-10-13 08:10:00 UTC');
        $this->runCron();

        self::assertSame(['Rendez-vous kiné'], $this->titles());
    }

    public function testTheGracePeriodEndsTenMinutesAfterTheReminder(): void
    {
        $user = $this->user('alice@example.com');
        $this->timed($user, 'Rendez-vous kiné', '+0 days', '10:00', [0]);
        $this->flushAndReset();

        $this->at('2026-10-13 08:10:00 UTC');
        $this->runCron();

        self::assertSame([], $this->titles());
    }

    public function testAReminderADayBeforeFallsOnTheDayBefore(): void
    {
        $user = $this->user('alice@example.com');
        $this->timed($user, 'Vol pour Lyon', '+1 days', '10:00', [1440]);
        $this->flushAndReset();

        $this->at('2026-10-13 08:00:00 UTC');
        $this->runCron();

        self::assertSame(['Vol pour Lyon'], $this->titles());
        self::assertSame('Dans 1 j · 10:00', $this->notificationOn('Vol pour Lyon')->getBody());
    }

    public function testTheTimeIsThatOfTheUsersTimezone(): void
    {
        $user = $this->user('newyork@example.com');
        $this->timezone($user, 'America/New_York');
        $this->timed($user, 'Appeler maman', '+0 days', '10:00', [0]);
        $this->flushAndReset();

        $this->at('2026-10-13 08:00:00 UTC');
        $this->runCron();
        self::assertSame([], $this->titles(), '10:00 in Paris is 04:00 in New York');

        $this->at('2026-10-13 14:00:00 UTC');
        $this->runCron();
        self::assertSame(['Appeler maman'], $this->titles(), '10:00 in New York (EDT)');
    }

    public function testATaskMovedToAnotherTimeIsRemindedAtTheNewOneOnly(): void
    {
        $user = $this->user('alice@example.com');
        $task = $this->timed($user, 'Rendez-vous kiné', '+0 days', '10:00', [0]);
        $this->flushAndReset();

        $moved = $this->em()->find(Task::class, $task->getId());
        self::assertInstanceOf(Task::class, $moved);
        $moved->setScheduledTime('11:00');
        $this->flushAndReset();

        $this->at('2026-10-13 08:00:00 UTC');
        $this->runCron();
        self::assertSame([], $this->titles(), '10:00 is no longer its time');

        $this->at('2026-10-13 09:00:00 UTC');
        $this->runCron();
        self::assertSame(['Rendez-vous kiné'], $this->titles());
    }

    public function testEachUserIsRemindedOfTheirOwnTasksOnly(): void
    {
        $this->timed($this->user('alice@example.com'), 'De Alice', '+0 days', '10:00', [0]);
        $this->timed($this->user('bob@example.com'), 'De Bob', '+0 days', '10:00', [0]);
        $this->flushAndReset();

        $this->at('2026-10-13 08:00:00 UTC');
        $this->runCron();

        self::assertSame(['De Alice'], $this->titlesOf('alice@example.com'));
        self::assertSame(['De Bob'], $this->titlesOf('bob@example.com'));
    }

    public function testNothingIsCreatedForAUserWhoTurnedNotificationsOff(): void
    {
        $user = $this->user('silent@example.com');
        $preference = (new UserPreference())->setUser($user)->setNotificationsEnabled(false);
        $this->em()->persist($preference);
        $this->timed($user, 'Rendez-vous kiné', '+0 days', '10:00', [0]);
        $this->flushAndReset();

        $this->at('2026-10-13 08:00:00 UTC');
        $this->runCron();

        self::assertSame([], $this->titles());
        self::assertSame(0, $this->pushesRequested());
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

    /**
     * @param string    $day       a relative day to the pinned morning, in the Paris calendar
     * @param list<int> $reminders
     */
    private function timed(User $user, string $title, string $day, string $time, array $reminders): Task
    {
        $task = $this->task($user, $title, TaskCriticality::Medium, null)
            ->schedule((new \DateTimeImmutable('2026-10-13', new \DateTimeZone('UTC')))->modify($day), TaskMoment::Time, $time, $reminders);

        return $task;
    }

    /** @return list<Notification> */
    private function notifications(): array
    {
        return $this->em()->getRepository(Notification::class)->findBy(['type' => NotificationType::TaskDue]);
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
     * The reminders sent, by the title of their task.
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
            $titles[] = $task->getTitle();
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
