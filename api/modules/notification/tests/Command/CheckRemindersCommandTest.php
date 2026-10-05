<?php

declare(strict_types=1);

namespace Maggie\Notification\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Notification\Command\CheckRemindersCommand;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Application;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * `maggie:notification:check-reminders` — the cron behind every event reminder.
 *
 * It is the only producer of a reminder notification, it has no HTTP surface, and
 * it had no test: the browser cannot run it, so an e2e journey cannot cover it
 * either (the smoke journey drives it over `docker compose exec` instead). What
 * it reads is also easy to get wrong and silent when it is — `getReminders()`
 * holds Google's `{useDefault, overrides: [{method, minutes}]}`, and the e2e
 * seed shipped a bare list for a while. `$reminders['overrides'] ?? []` is then
 * empty, so nothing fires, nothing logs, and the chain looks healthy.
 *
 * A recurring event is the other half of that silence (MAG-121): it is one row
 * whose start is its first occurrence, so the command expands its series and
 * files what it sent against the occurrence rather than against the row. The
 * fixtures' series are in UTC and counted in hours for that reason — see the
 * comment beside them.
 *
 * Reminders carry no Mercure publication of their own — the notification the
 * command creates does, through `CreateNotificationCommand`.
 */
final class CheckRemindersCommandTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    private CommandTester $tester;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $command = self::getContainer()->get(CheckRemindersCommand::class);
        $application = new Application();
        $application->add($command);

        $this->tester = new CommandTester($application->find('maggie:notification:check-reminders'));
    }

    public function testADueReminderBecomesANotificationForTheAgendaOwner(): void
    {
        $this->loadFixtures('CheckRemindersCommandTest.yaml');

        $this->runCommand();

        $notification = $this->reminderFor('Appel avec la banque');
        self::assertNotNull($notification, 'the due reminder produced no notification');
        self::assertSame(NotificationType::Reminder, $notification->getType());
        // The body carries the minutes, which is what `reminderExists` keys on.
        self::assertSame('60', $notification->getBody());
        $event = $this->event('Appel avec la banque');
        self::assertSame('/api/events/'.$event->getId(), $notification->getRelatedEntityIri());
        // An event that happens once is its own single occurrence.
        self::assertSame(
            $event->getStartAt()->format('c'),
            $notification->getOccurrenceStartAt()?->format('c'),
        );
        self::assertNull($notification->getReadAt());
        self::assertSame('fixture@example.com', $notification->getUser()->getEmail());

        // The two things that carry it to the owner, and both are silent when they
        // break: the bell lights up from a Mercure update, and the notification is
        // only readable from `/api/notifications` once it is indexed. A reminder
        // written to Postgres alone is a reminder nobody ever receives.
        $this->assertMercureUpdatePublished('/api/notifications/');
        $this->assertElasticsearchIndexDispatched(Notification::class);
    }

    public function testEachOwnerGetsOnlyTheirOwnReminder(): void
    {
        $this->loadFixtures('CheckRemindersCommandTest.yaml');

        $this->runCommand();

        // The command runs for every user — it is a system job — so the
        // neighbour's reminder has to fire too, and land in their inbox.
        self::assertSame(['Appel avec la banque', 'Point hebdomadaire'], $this->titlesFor('fixture@example.com'));
        self::assertSame(['Appel du voisin'], $this->titlesFor('other@example.com'));
    }

    /**
     * Everything the command must leave alone, in one assertion.
     *
     * Listed as the complement of what fired rather than row by row: the useful
     * question is "did anything else fire", and a per-row test would still pass
     * on a command that fired for a sixth reason nobody thought of.
     */
    public function testNothingElseFires(): void
    {
        $this->loadFixtures('CheckRemindersCommandTest.yaml');

        $this->runCommand();

        self::assertEqualsCanonicalizing(
            ['Appel avec la banque', 'Appel du voisin', 'Point hebdomadaire'],
            $this->allReminderTitles(),
            'only the three due reminders may fire — not the one still ahead, '
            .'not the one beyond 24 hours, not the one already started, not the malformed list, not 0 minutes, '
            .'not the one with none, not the series whose next occurrence is not due yet',
        );
    }

    public function testASecondRunDoesNotSendTheSameReminderTwice(): void
    {
        $this->loadFixtures('CheckRemindersCommandTest.yaml');

        $this->runCommand();
        $this->runCommand();

        // The cron runs every few minutes; without the dedup on (event,
        // occurrence, minutes) the owner would get the same reminder on every
        // tick until the event.
        self::assertEqualsCanonicalizing(
            ['Appel avec la banque', 'Appel du voisin', 'Point hebdomadaire'],
            $this->allReminderTitles(),
        );
    }

    /**
     * A recurring event is reminded of on the occurrence coming up, not on its first.
     *
     * The row the cron reads carries the series' first occurrence, which is a week
     * behind us here. Reading the rows alone, the trigger time was computed off
     * that past start and the "a reminder after the start is noise" guard then
     * dropped the whole series — so the owner was reminded of the first standup
     * and of nothing after it (MAG-121).
     */
    public function testARecurringEventIsRemindedOfOnTheOccurrenceComingUp(): void
    {
        $this->loadFixtures('CheckRemindersCommandTest.yaml');
        $master = $this->event('Point hebdomadaire');
        $expected = $master->getStartAt()->modify('+1 week');

        $this->runCommand();

        $notification = $this->reminderFor('Point hebdomadaire');
        self::assertNotNull($notification, 'the series produced no reminder for its next occurrence');
        self::assertSame('1440', $notification->getBody());
        // The occurrence, not the row's own start: it is what makes this reminder
        // a different one from last week's, and the series has a single row.
        self::assertSame(
            $expected->format('c'),
            $notification->getOccurrenceStartAt()?->format('c'),
            'the reminder was filed against the series rather than against the occurrence',
        );
        self::assertSame('/api/events/'.$master->getId(), $notification->getRelatedEntityIri());
    }

    /**
     * Last week's reminder does not silence this week's.
     *
     * The one assertion the dedup column exists for, and the one every other test
     * here would pass without it: a key of (event, minutes) names the series, so
     * the reminder already on file for the first occurrence looked like this one.
     */
    public function testAReminderAlreadySentForAnotherOccurrenceDoesNotSilenceThisOne(): void
    {
        $this->loadFixtures('CheckRemindersCommandTest.yaml');
        $master = $this->event('Point hebdomadaire');
        $this->fileReminder($master, 1440, $master->getStartAt());

        $this->runCommand();

        self::assertSame(
            [$master->getStartAt()->format('c'), $master->getStartAt()->modify('+1 week')->format('c')],
            $this->remindedOccurrencesOf('Point hebdomadaire'),
            'the occurrence coming up was taken for the one already reminded of',
        );
    }

    /**
     * An occurrence the owner cancelled is not reminded of.
     *
     * The exception row is built from the master rather than from a fixture on
     * purpose: the occurrence it replaces is matched to the second, and a literal
     * "+6 hours" written beside a "-162 hours" is the same instant only until one
     * of the two is evaluated a second later.
     */
    public function testACancelledOccurrenceIsNotRemindedOf(): void
    {
        $this->loadFixtures('CheckRemindersCommandTest.yaml');
        $master = $this->event('Point hebdomadaire');
        $this->cancelOccurrence($master, $master->getStartAt()->modify('+1 week'));

        $this->runCommand();

        self::assertSame([], $this->remindedOccurrencesOf('Point hebdomadaire'));
    }

    public function testTheCommandIsRegisteredUnderTheNameTheCrontabRuns(): void
    {
        // Nothing runs it but the crontab baked into the image (.docker/php/crontab,
        // every minute): a rename without the crontab line is silent. The crontab
        // sits outside the api/ tree the test container mounts, so this guards the
        // name; cron-image.test.sh checks the line.
        $application = new \Symfony\Bundle\FrameworkBundle\Console\Application(self::$kernel);

        self::assertTrue($application->has('maggie:notification:check-reminders'));
    }

    public function testItSaysSoWhenNothingIsDue(): void
    {
        $this->loadFixtures('CheckRemindersCommandTest.yaml');

        $this->runCommand();
        $this->tester->execute([]);

        self::assertStringContainsString('No due reminders found.', $this->tester->getDisplay());
    }

    private function runCommand(): void
    {
        $this->tester->execute([]);
        $this->tester->assertCommandIsSuccessful();
        $this->entityManager()->clear();
    }

    /** @return string[] */
    private function allReminderTitles(): array
    {
        return array_map(
            fn (Notification $notification) => $notification->getTitle(),
            $this->repository(Notification::class)->findBy(['type' => NotificationType::Reminder], ['title' => 'ASC']),
        );
    }

    /** @return string[] */
    private function titlesFor(string $email): array
    {
        $user = $this->repository(User::class)->findOneBy(['email' => $email]);
        self::assertInstanceOf(User::class, $user);

        return array_map(
            fn (Notification $notification) => $notification->getTitle(),
            $this->repository(Notification::class)->findBy(
                ['type' => NotificationType::Reminder, 'user' => $user],
                ['title' => 'ASC'],
            ),
        );
    }

    /**
     * The occurrences of one event the owner was reminded of, oldest first.
     *
     * @return list<string>
     */
    private function remindedOccurrencesOf(string $title): array
    {
        $notifications = $this->repository(Notification::class)->findBy(
            ['type' => NotificationType::Reminder, 'title' => $title],
            ['occurrenceStartAt' => 'ASC'],
        );

        return array_map(
            fn (Notification $notification) => (string) $notification->getOccurrenceStartAt()?->format('c'),
            $notifications,
        );
    }

    /** A reminder already on file, as a previous run of the cron would have left it. */
    private function fileReminder(\Maggie\Calendar\Entity\Event $event, int $minutes, \DateTimeImmutable $occurrenceStartAt): void
    {
        $notification = new Notification();
        $notification->setUser($event->getAgenda()->getUser());
        $notification->setType(NotificationType::Reminder);
        $notification->setTitle($event->getSummary());
        $notification->setBody((string) $minutes);
        $notification->setRelatedEntityIri('/api/events/'.$event->getId());
        $notification->setOccurrenceStartAt($occurrenceStartAt);

        $this->entityManager()->persist($notification);
        $this->entityManager()->flush();
        $this->entityManager()->clear();
    }

    /** Cancels one occurrence of a series, the way the agenda does: an exception instance. */
    private function cancelOccurrence(\Maggie\Calendar\Entity\Event $master, \DateTimeImmutable $occurrenceStartAt): void
    {
        $exception = new \Maggie\Calendar\Entity\Event();
        $exception->setSummary($master->getSummary());
        $exception->setStartAt($occurrenceStartAt);
        $exception->setEndAt($occurrenceStartAt->modify('+1 hour'));
        $exception->setTimeZone($master->getTimeZone());
        $exception->setAgenda($master->getAgenda());
        $exception->setRecurringEvent($master);
        $exception->setOriginalStartAt($occurrenceStartAt);
        $exception->setStatus(\Maggie\Calendar\Enum\EventStatus::Cancelled);

        $this->entityManager()->persist($exception);
        $this->entityManager()->flush();
        $this->entityManager()->clear();
    }

    private function reminderFor(string $title): ?Notification
    {
        return $this->repository(Notification::class)->findOneBy([
            'type' => NotificationType::Reminder,
            'title' => $title,
        ]);
    }

    private function event(string $summary): \Maggie\Calendar\Entity\Event
    {
        $event = $this->repository(\Maggie\Calendar\Entity\Event::class)->findOneBy(['summary' => $summary]);
        self::assertInstanceOf(\Maggie\Calendar\Entity\Event::class, $event);

        return $event;
    }

    /**
     * @param class-string $entity
     *
     * @return \Doctrine\ORM\EntityRepository<object>
     */
    private function repository(string $entity): \Doctrine\ORM\EntityRepository
    {
        return $this->entityManager()->getRepository($entity);
    }

    private function entityManager(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
