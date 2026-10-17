<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Allocating a transfer to créances or dettes (MAG-453): what the transfer
 * is, what its allocations give back, and the allocations themselves, one per
 * share of a debt.
 */
final class Version20261017120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add transaction.peer_kind and transaction.peer_allocated_cents, create peer_debt_allocation';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction ADD peer_kind VARCHAR(20) DEFAULT NULL');
        $this->addSql('ALTER TABLE transaction ADD peer_allocated_cents INT DEFAULT 0 NOT NULL');
        $this->addSql('CREATE TABLE peer_debt_allocation (id UUID NOT NULL, amount_cents INT NOT NULL, transaction_id UUID NOT NULL, share_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_BD4CF98A2FC0CB0F ON peer_debt_allocation (transaction_id)');
        $this->addSql('CREATE INDEX IDX_BD4CF98A2AE63FDB ON peer_debt_allocation (share_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_peer_debt_allocation_transaction_share ON peer_debt_allocation (transaction_id, share_id)');
        $this->addSql('ALTER TABLE peer_debt_allocation ADD CONSTRAINT FK_BD4CF98A2FC0CB0F FOREIGN KEY (transaction_id) REFERENCES transaction (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE peer_debt_allocation ADD CONSTRAINT FK_BD4CF98A2AE63FDB FOREIGN KEY (share_id) REFERENCES peer_debt_share (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE peer_debt_allocation DROP CONSTRAINT FK_BD4CF98A2FC0CB0F');
        $this->addSql('ALTER TABLE peer_debt_allocation DROP CONSTRAINT FK_BD4CF98A2AE63FDB');
        $this->addSql('DROP TABLE peer_debt_allocation');
        $this->addSql('ALTER TABLE transaction DROP peer_allocated_cents');
        $this->addSql('ALTER TABLE transaction DROP peer_kind');
    }
}
