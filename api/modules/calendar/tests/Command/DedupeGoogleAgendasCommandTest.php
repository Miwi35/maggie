<?php

namespace Maggie\Calendar\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\Exception\UniqueConstraintViolationException;
use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Event\PreRemoveEventArgs;
use Doctrine\ORM\Events;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\PullFromGoogleCommand;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Maggie\Core\Elasticsearch\IndexableEntityRegistry;
use Maggie\Core\Elasticsearch\IndexManager;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Elasticsearch\MessageHandler\DeleteDocumentHandler;
use Maggie\Core\Entity\User;
use Maggie\Core\Tests\Elasticsearch\RecordingElasticsearchTrait;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Tester\CommandTester;
use Symfony\Component\Uid\Ulid;

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
    use RecordingElasticsearchTrait;

    private const string INDEX = 'uniq_agenda_user_google_calendar';

    /** @var array<string, array<string, true>> index name → ids of the documents it holds */
    private array $searchIndex = [];
    private bool $searchEngineDown = false;
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

        $this->searchIndex = [];
        $this->searchEngineDown = false;
        $this->requests = [];
        $reader = new IndexMetadataReader();
        self::getContainer()->set(IndexManager::class, new IndexManager(
            $this->recordingClient($this->searchEngine(...)),
            $reader,
            new IndexableEntityRegistry($this->em(), $reader),
            new NullLogger(),
        ));

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

    /**
     * Elasticsearch as far as the merge needs it: a document list per index
     * that can be refreshed, scrolled and deleted from.
     *
     * @return array{int, array<string, mixed>}
     */
    private function searchEngine(string $method, string $path): array
    {
        if ($this->searchEngineDown) {
            return [500, ['error' => 'down']];
        }

        if (1 === preg_match('#^/([^/_]+)/_search$#', $path, $match)) {
            $hits = array_map(
                static fn (string $id): array => ['_id' => $id],
                array_keys($this->searchIndex[$match[1]] ?? []),
            );

            return [200, ['_scroll_id' => 'scroll', 'hits' => ['hits' => $hits]]];
        }

        if ('POST' === $method && '/_search/scroll' === $path) {
            return [200, ['_scroll_id' => 'scroll', 'hits' => ['hits' => []]]];
        }

        if (1 === preg_match('#^/([^/]+)/_doc/([^/]+)$#', $path, $match) && 'DELETE' === $method) {
            if (!isset($this->searchIndex[$match[1]][$match[2]])) {
                return [404, ['result' => 'not_found']];
            }
            unset($this->searchIndex[$match[1]][$match[2]]);

            return [200, ['result' => 'deleted']];
        }

        return [200, ['acknowledged' => true]];
    }

    /** The documents a healthy production holds before the merge: one per row. */
    private function indexEveryRow(): void
    {
        $connection = $this->em()->getConnection();
        $toBase32 = static fn (string $id): string => Ulid::fromString($id)->toBase32();

        foreach (['events' => 'event', 'agendas' => 'agenda'] as $index => $table) {
            foreach ($connection->fetchFirstColumn('SELECT id FROM '.$table) as $id) {
                $this->searchIndex[$index][$toBase32($id)] = true;
            }
        }
    }

    /** What the workers do with the messages the merge left on the queue. */
    private function handleDeletions(): void
    {
        $handler = new DeleteDocumentHandler(self::getContainer()->get(IndexManager::class), new NullLogger());
        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            $message = $envelope->getMessage();
            if ($message instanceof DeleteDocumentCommand) {
                $handler($message);
            }
        }
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

    public function testTheCopyGoogleUpdatedLastIsTheOneThatSurvives(): void
    {
        $this->loadDuplicates();

        $this->tester->execute([]);
        $this->tester->assertCommandIsSuccessful();

        $this->em()->clear();
        $event = $this->em()->getRepository(Event::class)->findOneBy(['googleEventId' => 'g-shared']);

        self::assertNotNull($event);
        self::assertSame(
            'Concert de Camille (renommé dans Google)',
            $event->getSummary(),
            'The kept agenda had stopped syncing, so its copy was the stale one',
        );
        self::assertEquals(new \DateTimeImmutable('2026-03-30 10:00'), $event->getGoogleUpdatedAt());
    }

    public function testTheDefaultAgendaDoesNotDisappearWithTheDuplicate(): void
    {
        [$kept] = $this->loadDuplicates();
        $keptId = (string) $kept->getId();

        $this->tester->execute([]);
        $this->tester->assertCommandIsSuccessful();

        $this->em()->clear();
        $defaults = $this->em()->getRepository(Agenda::class)->findBy(['isDefault' => true]);

        self::assertSame(
            [$keptId],
            array_map(fn (Agenda $agenda) => (string) $agenda->getId(), $defaults),
            'The flag moves to the agenda that is kept, so Maggie still knows where to file an appointment',
        );
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

    public function testTheSearchIndexHoldsExactlyTheRowsOnceTheMessagesAreHandled(): void
    {
        $this->loadDuplicates();
        $this->indexEveryRow();

        $this->tester->execute([]);
        $this->tester->assertCommandIsSuccessful();
        $this->handleDeletions();

        $this->assertSearchIndexMatchesTheDatabase();
    }

    /**
     * MAG-185: a deploy left one event in the index that the database no longer
     * had, which turned `app:elasticsearch:status --check` red and rolled the
     * production back. The table loses a row without a message whenever the
     * database cascades — here an event a sync writes into the duplicate
     * agenda while it is being deleted, after the list of its events was taken.
     */
    public function testAnEventTheDatabaseCascadesAwayDoesNotLeaveADocumentBehind(): void
    {
        [$kept, $duplicate] = $this->loadDuplicates();
        $duplicateId = (string) $duplicate->getId();
        $this->indexEveryRow();

        $connection = $this->em()->getConnection();
        $strayId = new Ulid();
        $this->em()->getEventManager()->addEventListener(
            Events::preRemove,
            new class($connection, $strayId, $duplicateId, $this) {
                public function __construct(
                    private readonly Connection $connection,
                    private readonly Ulid $strayId,
                    private readonly string $duplicateId,
                    private readonly DedupeGoogleAgendasCommandTest $test,
                ) {
                }

                public function preRemove(PreRemoveEventArgs $args): void
                {
                    if (!$args->getObject() instanceof Agenda) {
                        return;
                    }

                    $columns = array_keys($this->connection->fetchAssociative(
                        'SELECT * FROM event WHERE google_event_id = :id',
                        ['id' => 'g-only-in-duplicate'],
                    ) ?: []);
                    $copied = array_map(static fn (string $column): string => match ($column) {
                        'id' => 'CAST(:id AS uuid)',
                        'agenda_id' => 'CAST(:agenda AS uuid)',
                        'google_event_id' => ':google',
                        default => $column,
                    }, $columns);
                    $this->connection->executeStatement(
                        sprintf(
                            "INSERT INTO event (%s) SELECT %s FROM event WHERE google_event_id = 'g-only-in-duplicate'",
                            implode(', ', $columns),
                            implode(', ', $copied),
                        ),
                        [
                            'id' => $this->strayId->toRfc4122(),
                            'agenda' => Ulid::fromString($this->duplicateId)->toRfc4122(),
                            'google' => 'g-written-meanwhile',
                        ],
                    );
                    $this->test->indexDocument('events', (string) $this->strayId);
                }
            },
        );

        $this->tester->execute([]);
        $this->tester->assertCommandIsSuccessful();
        $this->handleDeletions();

        self::assertFalse(
            $this->em()->getConnection()->fetchOne('SELECT id FROM event WHERE google_event_id = ?', ['g-written-meanwhile']),
            'The cascade did remove the row — the scenario holds',
        );
        $this->assertSearchIndexMatchesTheDatabase();
    }

    public function testAMergeFailsLoudlyWhenTheSearchIndexCannotBeChecked(): void
    {
        $this->loadDuplicates();
        $this->searchEngineDown = true;
        $tester = $this->tester;

        $tester->execute([]);

        self::assertSame(1, $tester->getStatusCode());
        self::assertStringContainsString('could not be checked', $tester->getDisplay());
    }

    public function testSomethingIndexedForARowThatNeverExistedIsRemovedToo(): void
    {
        $this->loadDuplicates();
        $this->indexEveryRow();
        $this->indexDocument('agendas', (string) new Ulid());

        $this->tester->execute([]);
        $this->handleDeletions();

        $this->assertSearchIndexMatchesTheDatabase();
    }

    public function indexDocument(string $index, string $id): void
    {
        $this->searchIndex[$index][$id] = true;
    }

    private function assertSearchIndexMatchesTheDatabase(): void
    {
        $connection = $this->em()->getConnection();
        $toBase32 = static fn (string $id): string => Ulid::fromString($id)->toBase32();

        foreach (['events' => 'event', 'agendas' => 'agenda'] as $index => $table) {
            $rows = array_map($toBase32, $connection->fetchFirstColumn('SELECT id FROM '.$table));
            $documents = array_keys($this->searchIndex[$index] ?? []);
            sort($rows);
            sort($documents);

            self::assertSame($rows, $documents, sprintf('The "%s" index and the table must list the same ids', $index));
        }
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
