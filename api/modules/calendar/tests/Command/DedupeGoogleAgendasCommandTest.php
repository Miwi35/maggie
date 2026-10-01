<?php

namespace Maggie\Calendar\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\DBAL\Exception\UniqueConstraintViolationException;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\PullFromGoogleCommand;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * Merging the agendas that share a Google calendar (MAG-148).
 *
 * The duplicate state cannot be created once the unique index is in place, so
 * each test that needs it drops the index first — which is also the database
 * the migration finds in production.
 */
class DedupeGoogleAgendasCommandTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string INDEX = 'uniq_agenda_user_google_calendar';

    private CommandTester $tester;
    /** @var list<array{string, string}> */
    private array $stopWatchCalls = [];
    /** @var list<string> */
    private array $deletedCalendars = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();

        // Nothing here may reach Google: the duplicate carries a watch channel,
        // and deleting it asks Google to stop pushing.
        $this->stopWatchCalls = [];
        $this->deletedCalendars = [];
        $apiClient = $this->createStub(GoogleCalendarApiClient::class);
        $apiClient->method('stopWatch')->willReturnCallback(
            function (User $user, string $channelId, string $resourceId): void {
                $this->stopWatchCalls[] = [$channelId, $resourceId];
            },
        );
        $apiClient->method('deleteCalendar')->willReturnCallback(
            function (User $user, string $calendarId): void {
                $this->deletedCalendars[] = $calendarId;
            },
        );
        self::getContainer()->set(GoogleCalendarApiClient::class, $apiClient);

        $application = new Application(self::$kernel);
        $this->tester = new CommandTester($application->find('app:calendar:dedupe-google-agendas'));
    }

    protected function tearDown(): void
    {
        // The schema is created once for the whole run, so the index a test
        // dropped has to come back — and it only can on an emptied table.
        $this->purgeDatabase();
        $this->createUniqueIndex();

        parent::tearDown();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function dropUniqueIndex(): void
    {
        $this->em()->getConnection()->executeStatement('DROP INDEX IF EXISTS '.self::INDEX);
    }

    private function createUniqueIndex(): void
    {
        $this->em()->getConnection()->executeStatement(
            'CREATE UNIQUE INDEX IF NOT EXISTS '.self::INDEX.' ON agenda (user_id, google_calendar_id)'
        );
    }

    /** @return array{Agenda, Agenda} the agenda that should survive, then the duplicate */
    private function loadDuplicates(): array
    {
        $this->dropUniqueIndex();
        $this->loadFixtures('DedupeGoogleAgendasCommandTest.yaml');

        /** @var Agenda $kept */
        $kept = $this->getFixture('agenda_kept');
        /** @var Agenda $duplicate */
        $duplicate = $this->getFixture('agenda_duplicate');

        self::assertLessThan(
            (string) $duplicate->getId(),
            (string) $kept->getId(),
            'The fixture declares the kept agenda first, so its ULID is the older one',
        );

        return [$kept, $duplicate];
    }

    /** @return Agenda[] */
    private function reloadAgendas(): array
    {
        $this->em()->clear();

        return $this->em()->getRepository(Agenda::class)->findAll();
    }

    public function testReportsNothingToMergeOnAHealthyDatabase(): void
    {
        $this->purgeDatabase();

        $this->tester->execute([]);

        $this->tester->assertCommandIsSuccessful();
        self::assertStringContainsString('No Google calendar is connected twice', $this->tester->getDisplay());
    }

    public function testDryRunLeavesTheDuplicateAlone(): void
    {
        [, $duplicate] = $this->loadDuplicates();
        $duplicateId = (string) $duplicate->getId();

        $this->tester->execute(['--dry-run' => true]);

        $this->tester->assertCommandIsSuccessful();
        self::assertStringContainsString('Dry run', $this->tester->getDisplay());
        self::assertNotNull($this->em()->getRepository(Agenda::class)->find($duplicateId));
    }

    public function testKeepsTheOldestAgendaAndMovesTheDuplicatesEventsIntoIt(): void
    {
        [$kept, $duplicate] = $this->loadDuplicates();
        $keptId = (string) $kept->getId();
        $duplicateId = (string) $duplicate->getId();

        $this->tester->execute([]);
        $this->tester->assertCommandIsSuccessful();

        $agendas = $this->reloadAgendas();
        self::assertCount(2, $agendas, 'The duplicate is gone, "Perso" is untouched');
        self::assertNull($this->em()->getRepository(Agenda::class)->find($duplicateId));

        $events = $this->em()->getRepository(Event::class)->findBy(['agenda' => $keptId]);
        $googleEventIds = array_map(fn (Event $event) => $event->getGoogleEventId(), $events);
        sort($googleEventIds);

        self::assertSame([
            'g-only-in-duplicate',
            'g-recurring',
            'g-recurring_20260415T160000Z',
            'g-shared',
        ], $googleEventIds, 'Every Google event is there once, under the agenda that was kept');

        self::assertCount(4, $this->em()->getRepository(Event::class)->findAll());
    }

    public function testTheOccurrenceOfARecurrenceSurvivesItsParentBeingDropped(): void
    {
        [$kept] = $this->loadDuplicates();
        $keptId = (string) $kept->getId();

        $this->tester->execute([]);
        $this->tester->assertCommandIsSuccessful();

        $this->em()->clear();
        $exception = $this->em()->getRepository(Event::class)
            ->findOneBy(['googleEventId' => 'g-recurring_20260415T160000Z']);

        self::assertNotNull($exception, 'The override is not collateral damage of the cascade');
        self::assertSame($keptId, (string) $exception->getAgenda()->getId());
        self::assertSame(
            'g-recurring',
            $exception->getRecurringEvent()?->getGoogleEventId(),
            'It now overrides the copy of the recurrence that was kept',
        );
    }

    public function testAsksGoogleForEveryEventAgainSoTheKeptCopiesAreUpToDate(): void
    {
        [$kept] = $this->loadDuplicates();
        $keptId = (string) $kept->getId();

        $this->tester->execute([]);

        $this->em()->clear();
        $reloaded = $this->em()->getRepository(Agenda::class)->find($keptId);
        self::assertNull($reloaded->getGoogleSyncToken(), 'A full sync is asked for, not a delta');

        $pulls = array_filter(
            array_map(fn ($envelope) => $envelope->getMessage(), $this->getAsyncTransport()->getSent()),
            fn ($message) => $message instanceof PullFromGoogleCommand,
        );
        self::assertCount(1, $pulls);
        self::assertSame($keptId, array_values($pulls)[0]->agendaId);
    }

    public function testTakesTheDroppedEventsAndTheDuplicateOutOfTheSearchIndex(): void
    {
        [, $duplicate] = $this->loadDuplicates();
        $duplicateId = (string) $duplicate->getId();

        $this->tester->execute([]);

        $deleted = [];
        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            $message = $envelope->getMessage();
            if ($message instanceof DeleteDocumentCommand) {
                $deleted[] = $message->indexName.'/'.$message->documentId;
            }
        }

        self::assertContains('agendas/'.$duplicateId, $deleted);
        self::assertCount(
            2,
            array_filter($deleted, fn (string $entry) => str_starts_with($entry, 'events/')),
            'The two copies of an event Google only has once',
        );
    }

    public function testTellsTheOpenScreensTheDuplicateIsGone(): void
    {
        [, $duplicate] = $this->loadDuplicates();
        $duplicateId = (string) $duplicate->getId();

        $this->tester->execute([]);

        $this->assertMercureUpdatePublished('/api/agendas/'.$duplicateId);

        $deletes = array_filter(
            $this->getMercureHub()->getUpdates(),
            fn ($update) => str_contains($update->getData(), '"deleted":true'),
        );
        self::assertNotEmpty($deletes, 'A removed event is published as deleted, not left on screen');
    }

    public function testStopsTheDuplicatesGoogleWatchChannelAndKeepsTheCalendar(): void
    {
        $this->loadDuplicates();

        $this->tester->execute([]);

        self::assertSame(
            [['channel-duplicate', 'resource-duplicate']],
            $this->stopWatchCalls,
            'A channel left alive would keep pushing changes for an agenda that is gone',
        );
        self::assertSame([], $this->deletedCalendars, 'The Google calendar belongs to the agenda that is kept');
    }

    public function testTheDatabaseRefusesASecondAgendaOnTheSameGoogleCalendar(): void
    {
        $this->purgeDatabase();
        $this->createUniqueIndex();

        $user = new User();
        $user->setEmail('unique@example.com');
        $user->setGoogleId('google-unique-id');
        $user->setName('Unique');
        $this->em()->persist($user);

        foreach (['Concerts', 'Concerts (bis)'] as $name) {
            $agenda = new Agenda();
            $agenda->setUser($user);
            $agenda->setName($name);
            $agenda->setGoogleCalendarId('concerts@group.calendar.google.com');
            $this->em()->persist($agenda);
        }

        $this->expectException(UniqueConstraintViolationException::class);
        $this->em()->flush();
    }

    public function testTwoAgendasWithoutAGoogleCalendarLiveTogether(): void
    {
        $this->purgeDatabase();
        $this->createUniqueIndex();

        $user = new User();
        $user->setEmail('plain@example.com');
        $user->setGoogleId('google-plain-id');
        $user->setName('Plain');
        $this->em()->persist($user);

        foreach (['Perso', 'Famille'] as $name) {
            $agenda = new Agenda();
            $agenda->setUser($user);
            $agenda->setName($name);
            $this->em()->persist($agenda);
        }

        $this->em()->flush();

        self::assertCount(2, $this->em()->getRepository(Agenda::class)->findAll());
    }
}
