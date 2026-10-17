<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Every categorization rule becomes an operation without recurrence
 * (MAG-478): the same id — so its IRI, its rank among equal priorities and
 * the lines it qualified stay its own — the same criteria, qualification,
 * priority and active state. The transactions it qualified keep their
 * category, their qualification and their provenance: `qualified_by_rule_id`
 * now names the operation, and the table of the rules goes.
 *
 * The series are not touched. The Elasticsearch documents are rebuilt by the
 * reindex the deploy runs after the migrations.
 */
final class Version20261016190100 extends AbstractMigration
{
    private const COLUMNS = 'id, user_id, category_id, label_pattern, match_type, direction, min_amount_cents, max_amount_cents, priority, is_active, qualification_mandatory, qualification_kind';

    public function getDescription(): string
    {
        return 'Move every categorization rule into the operations, as an operation without recurrence, and point the transactions it qualified at it';
    }

    public function up(Schema $schema): void
    {
        $this->write(sprintf(
            'MAG-478: %d rule(s) become operations without recurrence; %d transaction(s) keep them as their provenance.',
            (int) $this->connection->fetchOne('SELECT COUNT(*) FROM categorization_rule'),
            (int) $this->connection->fetchOne('SELECT COUNT(*) FROM transaction WHERE qualified_by_rule_id IS NOT NULL'),
        ));

        $this->addSql(sprintf(
            "INSERT INTO recurring_operation (%s, day_rule, reference_source, amount_tolerance_percent, date_tolerance_days) SELECT %s, 'fixed_day', 'measured', 20, 5 FROM categorization_rule",
            self::COLUMNS,
            self::COLUMNS,
        ));
        // The constraint keeps its name: Doctrine names it after the column, not the table it points to.
        $this->addSql('ALTER TABLE transaction DROP CONSTRAINT FK_723705D194DD7FEE');
        $this->addSql('ALTER TABLE transaction ADD CONSTRAINT FK_723705D194DD7FEE FOREIGN KEY (qualified_by_rule_id) REFERENCES recurring_operation (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('DROP TABLE categorization_rule');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('CREATE TABLE categorization_rule (id UUID NOT NULL, label_pattern VARCHAR(255) DEFAULT NULL, match_type VARCHAR(20) NOT NULL, direction VARCHAR(20) NOT NULL, min_amount_cents INT DEFAULT NULL, max_amount_cents INT DEFAULT NULL, priority INT NOT NULL, is_active BOOLEAN NOT NULL, qualification_mandatory BOOLEAN DEFAULT false NOT NULL, qualification_kind VARCHAR(20) DEFAULT \'none\' NOT NULL, category_id UUID NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_F056C98A12469DE2 ON categorization_rule (category_id)');
        $this->addSql('CREATE INDEX IDX_F056C98AA76ED395 ON categorization_rule (user_id)');
        $this->addSql('ALTER TABLE categorization_rule ADD CONSTRAINT FK_F056C98A12469DE2 FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE categorization_rule ADD CONSTRAINT FK_F056C98AA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql(sprintf('INSERT INTO categorization_rule (%s) SELECT %s FROM recurring_operation WHERE period IS NULL', self::COLUMNS, self::COLUMNS));
        $this->addSql('ALTER TABLE transaction DROP CONSTRAINT FK_723705D194DD7FEE');
        $this->addSql('ALTER TABLE transaction ADD CONSTRAINT FK_723705D194DD7FEE FOREIGN KEY (qualified_by_rule_id) REFERENCES categorization_rule (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('DELETE FROM recurring_operation WHERE period IS NULL');
    }
}
