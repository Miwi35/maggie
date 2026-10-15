<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The qualification carried by the operation and copied onto the transaction
 * (MAG-476): `mandatory` and `kind` on the transactions, the recurring
 * operations and the categorization rules, and on the transaction the series
 * or the rule that set it. The data follows in Version20261015140100.
 */
final class Version20261015140000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add the qualification (mandatory, kind) to transactions, recurring operations and categorization rules, and who qualified a transaction';
    }

    public function up(Schema $schema): void
    {
        foreach (['transaction', 'recurring_operation', 'categorization_rule'] as $table) {
            $this->addSql(sprintf('ALTER TABLE %s ADD qualification_mandatory BOOLEAN DEFAULT false NOT NULL', $table));
            $this->addSql(sprintf("ALTER TABLE %s ADD qualification_kind VARCHAR(20) DEFAULT 'none' NOT NULL", $table));
        }

        $this->addSql('ALTER TABLE transaction ADD qualified_by_series_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE transaction ADD qualified_by_rule_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE transaction ADD CONSTRAINT FK_723705D168965E6F FOREIGN KEY (qualified_by_series_id) REFERENCES recurring_operation (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('ALTER TABLE transaction ADD CONSTRAINT FK_723705D194DD7FEE FOREIGN KEY (qualified_by_rule_id) REFERENCES categorization_rule (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_723705D168965E6F ON transaction (qualified_by_series_id)');
        $this->addSql('CREATE INDEX IDX_723705D194DD7FEE ON transaction (qualified_by_rule_id)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction DROP CONSTRAINT FK_723705D168965E6F');
        $this->addSql('ALTER TABLE transaction DROP CONSTRAINT FK_723705D194DD7FEE');
        $this->addSql('DROP INDEX IDX_723705D168965E6F');
        $this->addSql('DROP INDEX IDX_723705D194DD7FEE');
        $this->addSql('ALTER TABLE transaction DROP qualified_by_series_id');
        $this->addSql('ALTER TABLE transaction DROP qualified_by_rule_id');

        foreach (['transaction', 'recurring_operation', 'categorization_rule'] as $table) {
            $this->addSql(sprintf('ALTER TABLE %s DROP qualification_mandatory', $table));
            $this->addSql(sprintf('ALTER TABLE %s DROP qualification_kind', $table));
        }
    }
}
