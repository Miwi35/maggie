<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Migration;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\DBAL\ArrayParameterType;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\Schema\Schema;
use DoctrineMigrations\Version20261016190100;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\UseCase\CategorizeTransaction;
use Psr\Log\AbstractLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Uid\Ulid;

/**
 * Every rule becomes an operation without recurrence (MAG-478). Each case
 * starts from the data as it stood before: the series in their table, the
 * rules in `categorization_rule` — the transactions pointing at them — and
 * the lines each qualified. Then the migration's own statements run against
 * the test Postgres.
 */
class OperationMigrationTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private const RULE_COLUMNS = 'id, label_pattern, match_type, direction, min_amount_cents, max_amount_cents, priority, is_active, qualification_mandatory, qualification_kind, category_id, user_id';

    private Connection $connection;

    /** @var array<string, string> rule name => id as Postgres stores it */
    private array $rules = [];

    /** @var list<string> */
    private array $report = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->connection = self::getContainer()->get('doctrine.dbal.default_connection');
        $this->loadFixtures(__DIR__.'/fixtures/operation_migration.yaml');
        $this->asBeforeTheMigration();
    }

    public function testEveryRuleBecomesTheSameOperationWithoutRecurrence(): void
    {
        $before = $this->connection->fetchAllAssociative(sprintf('SELECT %s FROM categorization_rule ORDER BY id', self::RULE_COLUMNS));

        $this->runMigration();

        self::assertSame($before, $this->connection->fetchAllAssociative(sprintf('SELECT %s FROM recurring_operation WHERE id IN (:ids) ORDER BY id', self::RULE_COLUMNS), ['ids' => array_values($this->rules)], ['ids' => ArrayParameterType::STRING]));
        foreach ($this->connection->fetchAllAssociative('SELECT period, anchor_on, reference_amount_cents, account_id, label, ends_on FROM recurring_operation WHERE id IN (:ids)', ['ids' => array_values($this->rules)], ['ids' => ArrayParameterType::STRING]) as $row) {
            self::assertSame(['period' => null, 'anchor_on' => null, 'reference_amount_cents' => null, 'account_id' => null, 'label' => null, 'ends_on' => null], $row);
        }
        self::assertContains('MAG-478: 4 rule(s) become operations without recurrence; 4 transaction(s) keep them as their provenance.', $this->report);
    }

    public function testTheSeriesAreLeftAsTheyWere(): void
    {
        $before = $this->series();

        $this->runMigration();

        self::assertSame($before, $this->series());
    }

    public function testEveryTransactionKeepsItsCategoryItsQualificationAndWhoSetIt(): void
    {
        $before = $this->transactions();

        $this->runMigration();

        self::assertSame($before, $this->transactions());
        self::assertSame($this->rules['brasserie'], $this->column('t_brasserie', 'qualified_by_rule_id'));
    }

    public function testTheProvenanceNowNamesTheOperationAndTheRulesTableIsGone(): void
    {
        $this->runMigration();

        self::assertSame('recurring_operation', $this->connection->fetchOne(<<<'SQL'
            SELECT ccu.table_name FROM information_schema.constraint_column_usage ccu
            WHERE ccu.constraint_name = 'fk_723705d194dd7fee'
            SQL));
        self::assertFalse($this->connection->createSchemaManager()->tablesExist(['categorization_rule']));

        $line = $this->line('t_resto');
        self::assertSame('rule', $line->getQualifiedBy());
        self::assertSame('Restaurants', $line->getQualifiedByRule()?->getCategory()->getName());
        self::assertFalse($line->getQualifiedByRule()->hasRecurrence());
    }

    public function testTheOperationsFileTheLinesAsTheRulesDid(): void
    {
        $this->runMigration();
        $categorize = self::getContainer()->get(CategorizeTransaction::class);

        // The highest priority first, a label or the amount alone: each line
        // goes to the operation that took the place of its rule.
        foreach (['t_resto' => 'resto', 't_brasserie' => 'brasserie', 't_salary' => 'salary'] as $fixture => $rule) {
            self::assertSame($this->rules[$rule], $categorize->match($this->line($fixture))?->getId()->toRfc4122(), $fixture);
        }
        // A paused rule is a paused operation: it files nothing more.
        self::assertNull($categorize->match($this->line('t_fuel')));

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame(['Loyer'], array_map(
            static fn (RecurringOperation $series) => $series->getLabel(),
            $em->getRepository(RecurringOperation::class)->findSeriesByUser($this->getFixture('test_user')),
        ));
    }

    /**
     * The rules' table, its rows and the constraint as the MAG-476 migrations
     * left them; the lines as the engine qualified them.
     */
    private function asBeforeTheMigration(): void
    {
        $this->connection->executeStatement('ALTER TABLE transaction DROP CONSTRAINT FK_723705D194DD7FEE');
        $this->connection->executeStatement("CREATE TABLE categorization_rule (id UUID NOT NULL, label_pattern VARCHAR(255) DEFAULT NULL, match_type VARCHAR(20) NOT NULL, direction VARCHAR(20) NOT NULL, min_amount_cents INT DEFAULT NULL, max_amount_cents INT DEFAULT NULL, priority INT NOT NULL, is_active BOOLEAN NOT NULL, qualification_mandatory BOOLEAN DEFAULT false NOT NULL, qualification_kind VARCHAR(20) DEFAULT 'none' NOT NULL, category_id UUID NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))");
        $this->connection->executeStatement('ALTER TABLE transaction ADD CONSTRAINT FK_723705D194DD7FEE FOREIGN KEY (qualified_by_rule_id) REFERENCES categorization_rule (id) ON DELETE SET NULL NOT DEFERRABLE');

        $this->rule('resto', 'RESTO', 'contains', 'any', null, null, 0, true, false, 'none', 'restaurants');
        $this->rule('brasserie', 'brasserie', 'starts_with', 'debit', null, null, 5, true, false, 'none', 'restaurants');
        $this->rule('salary', null, 'contains', 'credit', 427500, 522500, 0, true, false, 'none', 'salary');
        $this->rule('fuel', 'TOTAL', 'contains', 'debit', null, null, 0, false, true, 'none', 'transport');

        $this->qualify('t_resto', 'restaurants', 'rule', rule: 'resto');
        $this->qualify('t_brasserie', 'restaurants', 'rule', rule: 'brasserie');
        $this->qualify('t_salary', 'salary', 'rule', rule: 'salary');
        $this->qualify('t_fuel', 'transport', 'rule', rule: 'fuel', mandatory: true);
        $this->qualify('t_by_hand', 'restaurants', 'manual', kind: 'saving');
        $this->qualify('t_orphan', 'restaurants', 'rule');
        $this->qualify('t_rent', 'housing', 'series', mandatory: true);
        $this->connection->executeStatement('UPDATE transaction SET qualified_by_series_id = :series, recurring_operation_id = :series, recurring_occurrence_on = :on WHERE id = :id', [
            'series' => $this->id('rent'),
            'on' => '2027-01-05',
            'id' => $this->id('t_rent'),
        ]);
    }

    private function rule(string $name, ?string $pattern, string $matchType, string $direction, ?int $min, ?int $max, int $priority, bool $active, bool $mandatory, string $kind, string $category): void
    {
        $this->rules[$name] = (new Ulid())->toRfc4122();
        $this->connection->insert('categorization_rule', [
            'id' => $this->rules[$name],
            'label_pattern' => $pattern,
            'match_type' => $matchType,
            'direction' => $direction,
            'min_amount_cents' => $min,
            'max_amount_cents' => $max,
            'priority' => $priority,
            'is_active' => $active,
            'qualification_mandatory' => $mandatory,
            'qualification_kind' => $kind,
            'category_id' => $this->id($category),
            'user_id' => $this->id('test_user'),
        ], ['is_active' => 'boolean', 'qualification_mandatory' => 'boolean']);
    }

    private function qualify(string $fixture, string $category, string $source, ?string $rule = null, bool $mandatory = false, string $kind = 'none'): void
    {
        $this->connection->executeStatement(
            'UPDATE transaction SET category_id = :category, category_source = :source, qualified_by_rule_id = :rule, qualification_mandatory = :mandatory, qualification_kind = :kind WHERE id = :id',
            [
                'category' => $this->id($category),
                'source' => $source,
                'rule' => null === $rule ? null : $this->rules[$rule],
                'mandatory' => $mandatory,
                'kind' => $kind,
                'id' => $this->id($fixture),
            ],
            ['mandatory' => 'boolean'],
        );
    }

    /** @return list<array<string, mixed>> */
    private function transactions(): array
    {
        return $this->connection->fetchAllAssociative('SELECT id, category_id, category_source, qualification_mandatory, qualification_kind, qualified_by_rule_id, qualified_by_series_id, recurring_operation_id FROM transaction ORDER BY id');
    }

    /** @return list<array<string, mixed>> */
    private function series(): array
    {
        return $this->connection->fetchAllAssociative('SELECT * FROM recurring_operation WHERE period IS NOT NULL ORDER BY id');
    }

    private function column(string $fixture, string $column): mixed
    {
        return $this->connection->fetchOne(sprintf('SELECT %s FROM transaction WHERE id = :id', $column), ['id' => $this->id($fixture)]);
    }

    private function line(string $fixture): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        $line = $em->find(Transaction::class, $this->getFixture($fixture)->getId());
        self::assertInstanceOf(Transaction::class, $line);

        return $line;
    }

    /** The id as Postgres stores it: a ULID column is a UUID. */
    private function id(string $fixture): string
    {
        return $this->getFixture($fixture)->getId()->toRfc4122();
    }

    private function runMigration(): void
    {
        // api/migrations is not on the autoloader — the migrations runner loads
        // the files itself, and this test runs *that* file.
        require_once \dirname(__DIR__, 4).'/migrations/Version20261016190100.php';

        $report = &$this->report;
        $migration = new Version20261016190100($this->connection, new class($report) extends AbstractLogger {
            /** @param list<string> $lines */
            public function __construct(private array &$lines)
            {
            }

            public function log($level, \Stringable|string $message, array $context = []): void
            {
                $this->lines[] = (string) $message;
            }
        });

        $migration->up(new Schema());
        foreach ($migration->getSql() as $query) {
            $this->connection->executeStatement($query->getStatement(), $query->getParameters(), $query->getTypes());
        }
    }
}
