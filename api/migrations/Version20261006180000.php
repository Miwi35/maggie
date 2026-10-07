<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * A movement between two of the owner's own accounts is neither an expense nor
 * an income (MAG-271).
 *
 * `transfer_kind` takes a line out of every aggregate, `transfer_source` tells
 * a detected pairing from one the owner decided, and `counterpart_id` names the
 * line in front. Nothing is flagged here: the existing history is paired by the
 * catch-up endpoint, which the owner runs. The defaults are dropped right after
 * being used, as `category_source` and `retrospect` do — they fill the rows
 * already stored, and the entity decides a new row's value.
 */
final class Version20261006180000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Let a transaction be an internal transfer: transaction.transfer_kind, transfer_source, counterpart_id';
    }

    public function up(Schema $schema): void
    {
        $this->addSql("ALTER TABLE transaction ADD transfer_kind VARCHAR(20) DEFAULT 'none' NOT NULL");
        $this->addSql("ALTER TABLE transaction ADD transfer_source VARCHAR(20) DEFAULT 'auto' NOT NULL");
        $this->addSql('ALTER TABLE transaction ADD counterpart_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE transaction ALTER COLUMN transfer_kind DROP DEFAULT');
        $this->addSql('ALTER TABLE transaction ALTER COLUMN transfer_source DROP DEFAULT');
        $this->addSql('ALTER TABLE transaction ADD CONSTRAINT FK_723705D1606374F2 FOREIGN KEY (counterpart_id) REFERENCES transaction (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_723705D1606374F2 ON transaction (counterpart_id)');
        $this->addSql('CREATE INDEX idx_transaction_user_transfer_kind ON transaction (user_id, transfer_kind)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction DROP CONSTRAINT FK_723705D1606374F2');
        $this->addSql('DROP INDEX idx_transaction_user_transfer_kind');
        $this->addSql('DROP INDEX IDX_723705D1606374F2');
        $this->addSql('ALTER TABLE transaction DROP counterpart_id');
        $this->addSql('ALTER TABLE transaction DROP transfer_source');
        $this->addSql('ALTER TABLE transaction DROP transfer_kind');
    }
}
