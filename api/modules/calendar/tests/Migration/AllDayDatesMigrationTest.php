<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Migration;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\DBAL\Connection;
use Doctrine\ORM\EntityManagerInterface;
use DoctrineMigrations\Version20261010040000;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\Attributes\DataProvider;
use Psr\Log\AbstractLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The migration that makes an all-day event a pair of dates (MAG-382).
 *
 * It runs the migration's own SQL against the test Postgres, from rows written
 * the three ways the old clients wrote a day: only a real database says what
 * `AT TIME ZONE` returns.
 */
class AllDayDatesMigrationTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private Connection $connection;

    /** @var list<string> */
    private array $report = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->connection = self::getContainer()->get('doctrine.dbal.default_connection');
    }

    /** The suite that follows expects the migrated schema. */
    protected function tearDown(): void
    {
        $this->runMigration('up');
        parent::tearDown();
    }

    /**
     * [convention, start_at, end_at as the old client stored them, in UTC, the first day and the end, excluded as Google stores it].
     *
     * @return iterable<string, array{string, string, string, string, string}>
     */
    public static function conventions(): iterable
    {
        // The owner's report: Google's 1st of January, its end the next midnight.
        yield 'google, one day' => ['google', '2037-01-01 00:00:00+00', '2037-01-02 00:00:00+00', '2037-01-01', '2037-01-02'];
        yield 'google, three days' => ['google', '2037-01-26 00:00:00+00', '2037-01-29 00:00:00+00', '2037-01-26', '2037-01-29'];
        // The admin's: 00:00Z to 23:59:59Z of the last day.
        yield 'admin, one day' => ['admin', '2037-01-01 00:00:00+00', '2037-01-01 23:59:59+00', '2037-01-01', '2037-01-02'];
        yield 'admin, three days' => ['admin', '2037-01-26 00:00:00+00', '2037-01-28 23:59:59+00', '2037-01-26', '2037-01-29'];
        // The mobile's: midnight in Paris, which is 23:00 the day before in UTC in winter.
        yield 'mobile, winter' => ['mobile', '2036-12-31 23:00:00+00', '2037-01-01 23:00:00+00', '2037-01-01', '2037-01-02'];
        yield 'mobile, summer, three days' => ['mobile', '2037-07-25 22:00:00+00', '2037-07-28 22:00:00+00', '2037-07-26', '2037-07-29'];
    }

    #[DataProvider('conventions')]
    public function testEachConventionLandsOnItsDaysWithNoInstantLeft(string $convention, string $startAt, string $endAt, string $first, string $until): void
    {
        $id = $this->anAllDayEventStoredAs($startAt, $endAt);

        $this->runMigration('up');

        self::assertSame(
            ['start_date' => $first, 'end_date' => $until, 'start_at' => null, 'end_at' => null],
            $this->connection->fetchAssociative('SELECT start_date, end_date, start_at, end_at FROM event WHERE id = :id', ['id' => $id]),
        );
        self::assertContains("  {$convention}: 1", $this->report);
    }

    /**
     * A weekly series the mobile wrote at midnight in Paris, its occurrence of
     * the 5th cancelled: the exception keyed it at 23:00Z on the 4th. Re-keyed
     * at midnight UTC of the 5th, it still cancels the 5th and nothing else.
     */
    public function testTheExceptionOfAnAllDaySeriesIsReKeyedOnItsDay(): void
    {
        $em = $this->freshEntityManager();
        $agenda = $this->anAgenda($em);
        $series = (new Event())->setSummary('Piscine')->setAgenda($agenda)->setRrule('FREQ=WEEKLY')
            ->scheduleTimed(new \DateTimeImmutable('2036-12-28T23:00:00Z'), new \DateTimeImmutable('2036-12-29T23:00:00Z'));
        $cancelled = (new Event())->setSummary('Piscine')->setAgenda($agenda)->setRecurringEvent($series)
            ->setOriginalStartAt(new \DateTimeImmutable('2037-01-04T23:00:00Z'))
            ->setStatus(\Maggie\Calendar\Enum\EventStatus::Cancelled)
            ->scheduleTimed(new \DateTimeImmutable('2037-01-04T23:00:00Z'), new \DateTimeImmutable('2037-01-05T23:00:00Z'));
        $em->persist($series);
        $em->persist($cancelled);
        $em->flush();
        [$seriesId, $exceptionId] = [$series->getId()->toRfc4122(), $cancelled->getId()->toRfc4122()];
        $em->clear();
        $this->runMigration('down');
        $this->connection->executeStatement('UPDATE event SET all_day = true WHERE id IN (:a, :b)', ['a' => $seriesId, 'b' => $exceptionId]);

        $this->runMigration('up');

        self::assertSame('2036-12-29', $this->connection->fetchOne('SELECT start_date FROM event WHERE id = :id', ['id' => $seriesId]));
        self::assertSame(
            '2037-01-05 00:00',
            $this->connection->fetchOne(
                "SELECT to_char(original_start_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') FROM event WHERE id = :id",
                ['id' => $exceptionId],
            ),
        );
    }

    public function testAMealIsItsOwnDay(): void
    {
        $id = $this->aMealOn('2037-01-01');

        $this->runMigration('up');

        self::assertSame(
            ['start_date' => '2037-01-01', 'end_date' => '2037-01-02', 'start_at' => null],
            $this->connection->fetchAssociative('SELECT start_date, end_date, start_at FROM event WHERE id = :id', ['id' => $id]),
        );
        self::assertContains('  meal: 1', $this->report);
    }

    public function testATimedEventIsLeftAlone(): void
    {
        $id = $this->anAllDayEventStoredAs('2037-01-01 09:00:00+00', '2037-01-01 10:00:00+00', allDay: false);

        $this->runMigration('up');

        $row = $this->connection->fetchAssociative('SELECT start_date, start_at FROM event WHERE id = :id', ['id' => $id]);
        self::assertNull($row['start_date']);
        self::assertNotNull($row['start_at']);
        self::assertContains('MAG-382: 0 all-day event(s) to convert to dates.', $this->report);
    }

    /** The dry run reports and writes nothing; running the migration again changes nothing. */
    public function testTheReportComesFirstAndASecondRunChangesNothing(): void
    {
        $id = $this->anAllDayEventStoredAs('2037-01-01 00:00:00+00', '2037-01-02 00:00:00+00');

        $migration = $this->migration();
        $migration->up(new \Doctrine\DBAL\Schema\Schema());
        self::assertContains('  google: 1', $this->report, 'the count is known before anything is written');
        self::assertNotNull($this->connection->fetchOne('SELECT start_at FROM event WHERE id = :id', ['id' => $id]), 'reporting wrote nothing');

        $this->execute($migration);
        $after = $this->connection->fetchAssociative('SELECT * FROM event WHERE id = :id', ['id' => $id]);

        $this->report = [];
        $again = $this->migration();
        $again->up(new \Doctrine\DBAL\Schema\Schema());
        $this->execute($again);

        self::assertSame($after, $this->connection->fetchAssociative('SELECT * FROM event WHERE id = :id', ['id' => $id]));
        self::assertContains('MAG-382: 0 all-day event(s) to convert to dates.', $this->report);
    }

    public function testItRollsBackToGooglesInstants(): void
    {
        $id = $this->anAllDayEventStoredAs('2037-01-26 00:00:00+00', '2037-01-28 23:59:59+00');
        $this->runMigration('up');

        $this->runMigration('down');

        self::assertFalse($this->hasDateColumns());
        self::assertSame(
            ['start_utc' => '2037-01-26 00:00', 'end_utc' => '2037-01-29 00:00'],
            $this->connection->fetchAssociative(
                "SELECT to_char(start_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') AS start_utc,
                        to_char(end_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') AS end_utc
                 FROM event WHERE id = :id",
                ['id' => $id],
            ),
        );
    }

    /** An event as the old schema held it: built through the ORM, then rolled back and forced to the old instants. */
    private function anAllDayEventStoredAs(string $startAt, string $endAt, bool $allDay = true): string
    {
        $em = $this->freshEntityManager();
        $event = (new Event())->setSummary('Journée')->setAgenda($this->anAgenda($em))
            ->scheduleTimed(new \DateTimeImmutable($startAt), new \DateTimeImmutable($endAt));
        $em->persist($event);
        $em->flush();
        $id = $event->getId()->toRfc4122();
        $em->clear();

        $this->runMigration('down');
        $this->connection->executeStatement(
            'UPDATE event SET start_at = :start, end_at = :end, all_day = :allDay WHERE id = :id',
            ['start' => $startAt, 'end' => $endAt, 'allDay' => $allDay ? 'true' : 'false', 'id' => $id],
        );
        $this->report = [];

        return $id;
    }

    /** A meal as the old schema held it: the whole day in Paris, all-day. */
    private function aMealOn(string $day): string
    {
        $em = $this->freshEntityManager();
        $meal = new Meal();
        $meal->setAgenda($this->anAgenda($em));
        $meal->setSlot(MealSlot::Dinner);
        $meal->setSummary('Dîner');
        $meal->setDate(new \DateTimeImmutable($day));
        $em->persist($meal);
        $em->flush();
        $id = $meal->getId()->toRfc4122();
        $em->clear();

        $this->runMigration('down');
        $this->connection->executeStatement(
            "UPDATE event SET start_at = (:day::date)::timestamp AT TIME ZONE 'Europe/Paris',
                end_at = ((:day::date)::timestamp + INTERVAL '23 hours 59 minutes 59 seconds') AT TIME ZONE 'Europe/Paris'
             WHERE id = :id",
            ['day' => $day, 'id' => $id],
        );
        $this->report = [];

        return $id;
    }

    private function freshEntityManager(): EntityManagerInterface
    {
        $this->purgeDatabase();

        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function anAgenda(EntityManagerInterface $em): Agenda
    {
        $user = new User();
        $user->setEmail('all-day-migration@example.com');
        $user->setGoogleId('google-all-day-migration');
        $user->setName('All Day Migration User');
        $em->persist($user);

        $agenda = new Agenda();
        $agenda->setUser($user);
        $agenda->setName('Perso');
        $em->persist($agenda);

        return $agenda;
    }

    private function migration(): Version20261010040000
    {
        // api/migrations is not on the autoloader — the migrations runner loads
        // the files itself, and this test runs *that* file.
        require_once \dirname(__DIR__, 4).'/migrations/Version20261010040000.php';

        $report = &$this->report;

        return new Version20261010040000($this->connection, new class($report) extends AbstractLogger {
            /** @param list<string> $lines */
            public function __construct(private array &$lines)
            {
            }

            public function log($level, \Stringable|string $message, array $context = []): void
            {
                $this->lines[] = (string) $message;
            }
        });
    }

    private function execute(Version20261010040000 $migration): void
    {
        foreach ($migration->getSql() as $query) {
            $this->connection->executeStatement($query->getStatement(), $query->getParameters(), $query->getTypes());
        }
    }

    private function runMigration(string $direction): void
    {
        if (('up' === $direction) === $this->hasDateColumns()) {
            return;
        }

        $migration = $this->migration();
        'up' === $direction
            ? $migration->up(new \Doctrine\DBAL\Schema\Schema())
            : $migration->down(new \Doctrine\DBAL\Schema\Schema());
        $this->execute($migration);
    }

    private function hasDateColumns(): bool
    {
        return (bool) $this->connection->fetchOne(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'event' AND column_name = 'start_date'",
        );
    }
}
