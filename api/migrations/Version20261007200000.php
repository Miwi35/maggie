<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Who a transaction was paid to or by, kept apart from its label (MAG-333).
 *
 * Nullable, no data migration: the existing history is filled by the next
 * bank sync for the movements it re-reads, and by
 * `app:finance:backfill-counterparty` for the rest.
 */
final class Version20261007200000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Keep the counterparty apart from the label: transaction.counterparty_name, counterparty_key';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction ADD counterparty_name VARCHAR(255) DEFAULT NULL');
        $this->addSql('ALTER TABLE transaction ADD counterparty_key VARCHAR(255) DEFAULT NULL');
        $this->addSql('CREATE INDEX idx_transaction_user_counterparty_key ON transaction (user_id, counterparty_key)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('DROP INDEX idx_transaction_user_counterparty_key');
        $this->addSql('ALTER TABLE transaction DROP counterparty_key');
        $this->addSql('ALTER TABLE transaction DROP counterparty_name');
    }
}
