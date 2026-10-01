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
        self::assertSame(
            '/api/events/'.$this->event('Appel avec la banque')->getId(),
            $notification->getRelatedEntityIri(),
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
        self::assertSame(['Appel avec la banque'], $this->titlesFor('fixture@example.com'));
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
            ['Appel avec la banque', 'Appel du voisin'],
            $this->allReminderTitles(),
            'only the two due reminders may fire — not the one still ahead, '
            .'not the one beyond 24 hours, not the malformed list, not 0 minutes, not the one with none',
        );
    }

    public function testASecondRunDoesNotSendTheSameReminderTwice(): void
    {
        $this->loadFixtures('CheckRemindersCommandTest.yaml');

        $this->runCommand();
        $this->runCommand();

        // The cron runs every few minutes; without the dedup on (event, minutes)
        // the owner would get the same reminder on every tick until the event.
        self::assertEqualsCanonicalizing(
            ['Appel avec la banque', 'Appel du voisin'],
            $this->allReminderTitles(),
        );
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
