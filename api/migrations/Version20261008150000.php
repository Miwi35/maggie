<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * A bank account is recognised by what identifies it at the bank, not by the
 * uid of the session that listed it (MAG-351): Enable Banking hands out new
 * uids with every consent, and each one became a second copy of the account.
 *
 * Schema only. Merging the copies already made is
 * `app:finance:merge-duplicate-accounts`, run by hand after a `--dry-run`.
 */
final class Version20261008150000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Identify bank accounts and movements by stable keys: account.external_key, account.closed_at, transaction.external_id';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE account ADD external_key VARCHAR(128) DEFAULT NULL');
        $this->addSql('ALTER TABLE account ADD closed_at TIMESTAMP(0) WITHOUT TIME ZONE DEFAULT NULL');
        $this->addSql('CREATE INDEX idx_account_user_external_key ON account (user_id, external_key)');
        $this->addSql('ALTER TABLE transaction ADD external_id VARCHAR(255) DEFAULT NULL');
        $this->addSql('CREATE INDEX idx_transaction_account_external_id ON transaction (account_id, external_id)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('DROP INDEX idx_transaction_account_external_id');
        $this->addSql('ALTER TABLE transaction DROP external_id');
        $this->addSql('DROP INDEX idx_account_user_external_key');
        $this->addSql('ALTER TABLE account DROP closed_at');
        $this->addSql('ALTER TABLE account DROP external_key');
    }
}
