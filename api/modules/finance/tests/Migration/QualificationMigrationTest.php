<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Migration;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\Schema\Schema;
use DoctrineMigrations\Version20261015140100;
use Psr\Log\AbstractLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The qualification taken from the flag of the category (MAG-476). Each case
 * starts from the data as it stood before: the categories carry the flags,
 * the transactions, the series and the rules carry nothing yet, and
 * `category_source` says who categorised each line. Then the migration's own
 * statements run against the test Postgres.
 */
class QualificationMigrationTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private Connection $connection;

    /** @var list<string> */
    private array $report = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->connection = self::getContainer()->get('doctrine.dbal.default_connection');
        $this->loadFixtures(__DIR__.'/fixtures/qualification_migration.yaml');
        $this->asBeforeTheMigration();
    }

    public function testEveryLineTakesTheQualificationItsCategoryFlagMeant(): void
    {
        $this->runMigration();

        self::assertSame([
            't_advance' => [false, 'repayment'],
            't_brasserie' => [false, 'none'],
            't_loan' => [true, 'repayment'],
            't_orphan' => [false, 'none'],
            't_overdraft' => [true, 'repayment'],
            't_rent' => [true, 'none'],
            't_rent_by_rule' => [true, 'none'],
            't_resto' => [false, 'none'],
            't_salary' => [false, 'none'],
            't_savings' => [false, 'saving'],
            't_stocks' => [false, 'investment'],
            't_uncategorized' => [false, 'none'],
        ], $this->qualifications('transaction'));
    }

    public function testTheTotalsByQualificationAreTheTotalsByFlagBeforeTheMigration(): void
    {
        $before = $this->connection->fetchAllAssociative(<<<'SQL'
            SELECT c.obligation IN ('mandatory', 'debt') AS mandatory,
                   CASE WHEN c.name LIKE 'Remboursement %' OR c.obligation = 'debt' THEN 'repayment'
                        WHEN c.obligation IN ('saving', 'investment') THEN c.obligation ELSE 'none' END AS kind,
                   COUNT(*) AS lines, SUM(t.amount_cents) AS cents
            FROM transaction t JOIN category c ON c.id = t.category_id GROUP BY 1, 2 ORDER BY 1, 2
            SQL);

        $this->runMigration();

        self::assertSame($before, $this->connection->fetchAllAssociative(<<<'SQL'
            SELECT qualification_mandatory AS mandatory, qualification_kind AS kind, COUNT(*) AS lines, SUM(amount_cents) AS cents
            FROM transaction WHERE category_id IS NOT NULL GROUP BY 1, 2 ORDER BY 1, 2
            SQL));
        self::assertContains('  mandatory=true kind=repayment: 2 line(s), -35000 cents', $this->report);
    }

    public function testWhoCategorisedALineIsWhoQualifiedIt(): void
    {
        $this->runMigration();

        self::assertSame($this->id('rent'), $this->column('t_rent', 'qualified_by_series_id'));
        // Both rules recognise it: the higher priority one filed it.
        self::assertSame($this->id('rule_brasserie'), $this->column('t_brasserie', 'qualified_by_rule_id'));
        self::assertSame($this->id('rule_resto'), $this->column('t_resto', 'qualified_by_rule_id'));
        self::assertSame($this->id('rule_rent'), $this->column('t_rent_by_rule', 'qualified_by_rule_id'));
        // Its label no longer says AVANCE, but it is the only rule of the category.
        self::assertSame($this->id('rule_advance'), $this->column('t_advance', 'qualified_by_rule_id'));
        foreach (['t_loan', 't_savings', 't_uncategorized'] as $byHandOrNobody) {
            self::assertNull($this->column($byHandOrNobody, 'qualified_by_rule_id'));
            self::assertNull($this->column($byHandOrNobody, 'qualified_by_series_id'));
        }
        self::assertSame('manual', $this->column('t_loan', 'category_source'));
        // No rule left to name: still filed by a rule, which one is unknown.
        self::assertSame('rule', $this->column('t_orphan', 'category_source'));
        self::assertNull($this->column('t_orphan', 'qualified_by_rule_id'));
        self::assertContains('MAG-476: 4 transaction(s) qualified by 4 rule(s).', $this->report);
    }

    public function testTheSeriesAndTheRulesTakeTheQualificationOfTheirCategory(): void
    {
        $this->runMigration();

        self::assertSame(['car_loan' => [true, 'repayment'], 'rent' => [true, 'none']], $this->qualifications('recurring_operation'));
        self::assertSame([
            'rule_advance' => [false, 'repayment'],
            'rule_brasserie' => [false, 'none'],
            'rule_rent' => [true, 'none'],
            'rule_resto' => [false, 'none'],
        ], $this->qualifications('categorization_rule'));
    }

    public function testTheReportComesFirstAndASecondRunChangesNothing(): void
    {
        $migration = $this->migration();
        $migration->up(new Schema());
        self::assertContains('  mandatory=false kind=saving: 1 line(s), -20000 cents', $this->report, 'the report is known before anything is written');
        self::assertSame('none', $this->column('t_savings', 'qualification_kind'), 'reporting wrote nothing');

        $this->execute($migration);
        $after = $this->snapshot();

        $this->runMigration();

        self::assertSame($after, $this->snapshot());
    }

    /**
     * The fixtures are written by today's code; the columns are put back as
     * the schema migration leaves them, with who categorised each line.
     */
    private function asBeforeTheMigration(): void
    {
        foreach (['transaction', 'recurring_operation', 'categorization_rule'] as $table) {
            $this->connection->executeStatement(sprintf("UPDATE %s SET qualification_mandatory = false, qualification_kind = 'none'", $table));
        }
        $this->connection->executeStatement("UPDATE transaction SET qualified_by_series_id = NULL, qualified_by_rule_id = NULL, category_source = CASE WHEN category_id IS NULL THEN 'none' ELSE 'manual' END");
        $this->connection->executeStatement("UPDATE transaction SET category_source = 'series' WHERE id = :id", ['id' => $this->id('t_rent')]);
        foreach (['t_brasserie', 't_resto', 't_rent_by_rule', 't_advance', 't_orphan'] as $fixture) {
            $this->connection->executeStatement("UPDATE transaction SET category_source = 'rule' WHERE id = :id", ['id' => $this->id($fixture)]);
        }
    }

    /** @return array<string, array{bool, string}> fixture => [mandatory, kind] */
    private function qualifications(string $table): array
    {
        $byId = [];
        foreach ($this->connection->fetchAllAssociative(sprintf('SELECT id, qualification_mandatory, qualification_kind FROM %s', $table)) as $row) {
            $byId[$row['id']] = [(bool) $row['qualification_mandatory'], $row['qualification_kind']];
        }

        $byFixture = [];
        foreach (array_keys(self::fixtureNames($table)) as $fixture) {
            $byFixture[$fixture] = $byId[$this->id($fixture)];
        }
        ksort($byFixture);

        return $byFixture;
    }

    /** @return array<string, true> */
    private static function fixtureNames(string $table): array
    {
        return array_fill_keys(match ($table) {
            'transaction' => ['t_rent', 't_brasserie', 't_resto', 't_rent_by_rule', 't_savings', 't_stocks', 't_loan', 't_salary', 't_advance', 't_overdraft', 't_orphan', 't_uncategorized'],
            'recurring_operation' => ['rent', 'car_loan'],
            'categorization_rule' => ['rule_resto', 'rule_brasserie', 'rule_rent', 'rule_advance'],
            default => throw new \LogicException($table),
        }, true);
    }

    private function column(string $fixture, string $column): mixed
    {
        return $this->connection->fetchOne(sprintf('SELECT %s FROM transaction WHERE id = :id', $column), ['id' => $this->id($fixture)]);
    }

    /** @return list<array<string, mixed>> */
    private function snapshot(): array
    {
        return [
            ...$this->connection->fetchAllAssociative('SELECT id, qualification_mandatory, qualification_kind, qualified_by_series_id, qualified_by_rule_id, category_source FROM transaction ORDER BY id'),
            ...$this->connection->fetchAllAssociative('SELECT id, qualification_mandatory, qualification_kind FROM recurring_operation ORDER BY id'),
            ...$this->connection->fetchAllAssociative('SELECT id, qualification_mandatory, qualification_kind FROM categorization_rule ORDER BY id'),
        ];
    }

    /** The id as Postgres stores it: a ULID column is a UUID. */
    private function id(string $fixture): string
    {
        return $this->getFixture($fixture)->getId()->toRfc4122();
    }

    private function migration(): Version20261015140100
    {
        // api/migrations is not on the autoloader — the migrations runner loads
        // the files itself, and this test runs *that* file.
        require_once \dirname(__DIR__, 4).'/migrations/Version20261015140100.php';

        $report = &$this->report;

        return new Version20261015140100($this->connection, new class($report) extends AbstractLogger {
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

    private function execute(Version20261015140100 $migration): void
    {
        foreach ($migration->getSql() as $query) {
            $this->connection->executeStatement($query->getStatement(), $query->getParameters(), $query->getTypes());
        }
    }

    private function runMigration(): void
    {
        $migration = $this->migration();
        $migration->up(new Schema());
        $this->execute($migration);
    }
}
