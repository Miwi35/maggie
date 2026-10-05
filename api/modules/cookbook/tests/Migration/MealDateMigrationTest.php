<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Migration;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\DBAL\Connection;
use Doctrine\ORM\EntityManagerInterface;
use DoctrineMigrations\Version20261005160000;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Enum\MealSlot;
use PHPUnit\Framework\Attributes\DataProvider;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The migration that makes a meal a day, both ways (MAG-251).
 *
 * It runs the migration's own SQL against the test Postgres, so the conversion
 * it asserts is the one that will run in production — the rule is a
 * `AT TIME ZONE 'Europe/Paris'`, and only a real database can say what that
 * returns on the day the clocks go back.
 *
 * Each case starts from what the broken week view actually wrote: midnight at
 * a hard-coded `+01:00`, which is 23:00 the day before in UTC for most of the
 * year. The day the owner asked for is what has to come back out.
 */
class MealDateMigrationTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private Connection $connection;

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
     * @return iterable<string, array{string, string}>
     */
    public static function daysTheOwnerAskedFor(): iterable
    {
        // [the day asked for, what "<day>T00:00:00+01:00" became in UTC]
        yield 'summer time, the week the owner reported' => ['2026-10-07', '2026-10-06 23:00:00+00'];
        yield 'winter time' => ['2026-12-07', '2026-12-06 23:00:00+00'];
        yield 'the day the clocks go back' => ['2026-10-25', '2026-10-24 23:00:00+00'];
    }

    #[DataProvider('daysTheOwnerAskedFor')]
    public function testTheDayComesBackOutOfTheInstantItWasWrittenAs(string $day, string $storedAsUtc): void
    {
        $mealId = $this->aMealStoredAs($day, $storedAsUtc);

        $this->runMigration('down');
        $this->runMigration('up');

        self::assertSame($day, $this->column($mealId, 'meal', 'date'));
    }

    /**
     * The instants follow the day: the whole day in Paris, all-day. The day the
     * clocks go back is the one that proves the offset is not hard-coded — it
     * opens at `+02:00` and closes at `+01:00`.
     */
    public function testTheInstantsAreRederivedFromTheDay(): void
    {
        $mealId = $this->aMealStoredAs('2026-10-25', '2026-10-24 23:00:00+00');

        $this->runMigration('down');
        $this->runMigration('up');

        $row = $this->connection->fetchAssociative(
            "SELECT to_char(start_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS start_utc,
                    to_char(end_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS end_utc,
                    all_day
             FROM event WHERE id = :id",
            ['id' => $mealId],
        );

        self::assertSame('2026-10-24 22:00:00', $row['start_utc']);
        self::assertSame('2026-10-25 22:59:59', $row['end_utc']);
        self::assertTrue((bool) $row['all_day']);
    }

    /** And back: the column goes, the meals stay, and the day is readable off the instant again. */
    public function testItRollsBack(): void
    {
        $mealId = $this->aMealStoredAs('2026-10-07', '2026-10-06 23:00:00+00');

        $this->runMigration('down');
        $this->runMigration('up');
        $this->runMigration('down');

        self::assertFalse($this->hasDateColumn(), 'the date column survived the rollback');
        self::assertSame('1', (string) $this->connection->fetchOne('SELECT COUNT(*) FROM meal WHERE id = :id', ['id' => $mealId]));
        self::assertSame(
            '2026-10-07',
            $this->connection->fetchOne(
                "SELECT to_char(start_at AT TIME ZONE 'Europe/Paris', 'YYYY-MM-DD') FROM event WHERE id = :id",
                ['id' => $mealId],
            ),
        );
    }

    /**
     * A meal planned for `$day`, whose stored instant is the one the broken
     * week view wrote. Built through the ORM — the row has every column the
     * event table needs — then the instant is forced back to the broken value.
     */
    private function aMealStoredAs(string $day, string $storedAsUtc): string
    {
        $this->purgeDatabase();

        /** @var EntityManagerInterface $em */
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        $user = new \Maggie\Core\Entity\User();
        $user->setEmail('meal-migration@example.com');
        $user->setGoogleId('google-meal-migration');
        $user->setName('Meal Migration User');
        $em->persist($user);

        $agenda = new Agenda();
        $agenda->setUser($user);
        $agenda->setName('Repas');
        $em->persist($agenda);

        $meal = new Meal();
        $meal->setAgenda($agenda);
        $meal->setSlot(MealSlot::Dinner);
        $meal->setSummary('Dîner');
        $meal->setDate(new \DateTimeImmutable($day));
        $em->persist($meal);
        $em->flush();

        // The id column is a Postgres uuid: a ULID has to be bound in its
        // RFC 4122 form, which is the same 128 bits.
        $id = $meal->getId()->toRfc4122();
        $em->clear();

        $this->connection->executeStatement(
            'UPDATE event SET start_at = :instant, end_at = :instant WHERE id = :id',
            ['instant' => $storedAsUtc, 'id' => $id],
        );

        return $id;
    }

    /** Runs the real migration's statements, in order, on the test database. */
    private function runMigration(string $direction): void
    {
        // api/migrations is not on the autoloader — the migrations runner loads
        // the files itself. The point of this test is to run *that* file, so it
        // loads it the same way.
        require_once \dirname(__DIR__, 4).'/migrations/Version20261005160000.php';

        $migration = new Version20261005160000($this->connection, new NullLogger());

        if ('up' === $direction) {
            if ($this->hasDateColumn()) {
                return;
            }
            $migration->up(new \Doctrine\DBAL\Schema\Schema());
        } else {
            if (!$this->hasDateColumn()) {
                return;
            }
            $migration->down(new \Doctrine\DBAL\Schema\Schema());
        }

        foreach ($migration->getSql() as $query) {
            $this->connection->executeStatement($query->getStatement(), $query->getParameters(), $query->getTypes());
        }
    }

    private function hasDateColumn(): bool
    {
        return (bool) $this->connection->fetchOne(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'meal' AND column_name = 'date'",
        );
    }

    private function column(string $id, string $table, string $column): ?string
    {
        $value = $this->connection->fetchOne(
            \sprintf('SELECT %s FROM %s WHERE id = :id', $this->connection->quoteIdentifier($column), $table),
            ['id' => $id],
        );

        return false === $value || null === $value ? null : (string) $value;
    }
}
