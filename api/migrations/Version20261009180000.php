<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Attaching transactions to recurring operations (MAG-305): which series a
 * line is an occurrence of, which occurrence, and who decided. Additive: every
 * existing line starts unattached, `auto`, free for the catch-up.
 */
final class Version20261009180000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Attach transactions to an occurrence of a recurring operation';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction ADD recurring_occurrence_on DATE DEFAULT NULL');
        $this->addSql("ALTER TABLE transaction ADD recurring_source VARCHAR(20) DEFAULT 'auto' NOT NULL");
        $this->addSql('ALTER TABLE transaction ADD recurring_operation_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE transaction ADD CONSTRAINT FK_723705D18A6CB683 FOREIGN KEY (recurring_operation_id) REFERENCES recurring_operation (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_723705D18A6CB683 ON transaction (recurring_operation_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_transaction_recurring_occurrence ON transaction (recurring_operation_id, recurring_occurrence_on)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('DROP INDEX uniq_transaction_recurring_occurrence');
        $this->addSql('ALTER TABLE transaction DROP CONSTRAINT FK_723705D18A6CB683');
        $this->addSql('DROP INDEX IDX_723705D18A6CB683');
        $this->addSql('ALTER TABLE transaction DROP recurring_operation_id');
        $this->addSql('ALTER TABLE transaction DROP recurring_source');
        $this->addSql('ALTER TABLE transaction DROP recurring_occurrence_on');
    }
}
