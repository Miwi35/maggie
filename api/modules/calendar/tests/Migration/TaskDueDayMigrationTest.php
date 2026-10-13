<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Migration;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\DBAL\Connection;
use DoctrineMigrations\Version20261013080000;
use Maggie\Calendar\Entity\Task;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserPreference;
use PHPUnit\Framework\Attributes\DataProvider;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The migration that makes a task's due date a day (MAG-404), run against the
 * test Postgres: only a real database says what `AT TIME ZONE` returns.
 */
class TaskDueDayMigrationTest extends KernelTestCase
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
     * [the instant stored, the owner's time zone or null for none set, the day it must become].
     *
     * @return iterable<string, array{string, ?string, string}>
     */
    public static function instants(): iterable
    {
        // The ticket's case, in summer time: 21:30 UTC.
        yield '23:30 in Paris, summer' => ['2026-06-20 21:30:00+00', 'Europe/Paris', '2026-06-20'];
        // What create_task wrote: midnight in Paris, the day before in UTC.
        yield 'midnight in Paris, winter' => ['2026-01-19 23:00:00+00', 'Europe/Paris', '2026-01-20'];
        yield 'no preference, read in Paris' => ['2026-01-19 23:00:00+00', null, '2026-01-20'];
        // An owner elsewhere keeps their own day.
        yield '23:30 in New York' => ['2026-03-21 03:30:00+00', 'America/New_York', '2026-03-20'];
    }

    #[DataProvider('instants')]
    public function testEachInstantKeepsItsDayInTheOwnersZone(string $stored, ?string $timeZone, string $day): void
    {
        $id = $this->aTaskStoredAs($stored, $timeZone);

        $this->runMigration('up');

        self::assertSame($day, $this->connection->fetchOne('SELECT due_date FROM task WHERE id = :id', ['id' => $id]));
    }

    public function testAnUndatedTaskStaysUndatedAndGetsNoLink(): void
    {
        $id = $this->aTaskStoredAs(null, null);

        $this->runMigration('up');

        self::assertSame(
            ['due_date' => null, 'parent_id' => null, 'position' => null, 'links' => '[]'],
            $this->connection->fetchAssociative('SELECT due_date, parent_id, position, links FROM task WHERE id = :id', ['id' => $id]),
        );
    }

    public function testItRollsBackToMidnightInTheOwnersZone(): void
    {
        $id = $this->aTaskStoredAs('2026-01-19 23:00:00+00', 'Europe/Paris');
        $this->runMigration('up');

        $this->runMigration('down');

        self::assertSame('2026-01-19 23:00', $this->connection->fetchOne(
            "SELECT to_char(due_date AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') FROM task WHERE id = :id",
            ['id' => $id],
        ));
    }

    /** A task as the old schema held it: built through the ORM, then rolled back and forced to the old instant. */
    private function aTaskStoredAs(?string $dueAt, ?string $timeZone): string
    {
        $this->purgeDatabase();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        $user = new User();
        $user->setEmail('task-day-migration@example.com');
        $user->setGoogleId('google-task-day-migration');
        $user->setName('Task Day Migration User');
        $em->persist($user);
        if (null !== $timeZone) {
            $em->persist((new UserPreference())->setUser($user)->setTimezone($timeZone));
        }
        $task = (new Task())->setUser($user)->setTitle('Échéance');
        $em->persist($task);
        $em->flush();
        $id = $task->getId()->toRfc4122();
        $em->clear();

        $this->runMigration('down');
        $this->connection->executeStatement('UPDATE task SET due_date = :due WHERE id = :id', ['due' => $dueAt, 'id' => $id]);

        return $id;
    }

    private function migration(): Version20261013080000
    {
        // api/migrations is not on the autoloader — the migrations runner loads
        // the files itself, and this test runs *that* file.
        require_once \dirname(__DIR__, 4).'/migrations/Version20261013080000.php';

        return new Version20261013080000($this->connection, new NullLogger());
    }

    private function runMigration(string $direction): void
    {
        if (('up' === $direction) === $this->isMigrated()) {
            return;
        }

        $migration = $this->migration();
        'up' === $direction
            ? $migration->up(new \Doctrine\DBAL\Schema\Schema())
            : $migration->down(new \Doctrine\DBAL\Schema\Schema());
        foreach ($migration->getSql() as $query) {
            $this->connection->executeStatement($query->getStatement(), $query->getParameters(), $query->getTypes());
        }
    }

    private function isMigrated(): bool
    {
        return (bool) $this->connection->fetchOne(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'task' AND column_name = 'parent_id'",
        );
    }
}
