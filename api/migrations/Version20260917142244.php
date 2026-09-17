<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20260917142244 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add bank connections and rename the account external id';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE bank_connection (id UUID NOT NULL, bank_name VARCHAR(255) NOT NULL, country VARCHAR(2) NOT NULL, status VARCHAR(20) NOT NULL, state VARCHAR(64) NOT NULL, session_id VARCHAR(255) DEFAULT NULL, consent_expires_at TIMESTAMP(0) WITHOUT TIME ZONE DEFAULT NULL, last_synced_at TIMESTAMP(0) WITHOUT TIME ZONE DEFAULT NULL, created_at TIMESTAMP(0) WITHOUT TIME ZONE NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX UNIQ_A057B5D0A393D2FB ON bank_connection (state)');
        $this->addSql('CREATE INDEX IDX_A057B5D0A76ED395 ON bank_connection (user_id)');
        $this->addSql('ALTER TABLE bank_connection ADD CONSTRAINT FK_A057B5D0A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE account ADD bank_connection_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE account RENAME COLUMN bridge_account_id TO external_account_id');
        $this->addSql('ALTER TABLE account ADD CONSTRAINT FK_7D3656A487756E0A FOREIGN KEY (bank_connection_id) REFERENCES bank_connection (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_7D3656A487756E0A ON account (bank_connection_id)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE bank_connection DROP CONSTRAINT FK_A057B5D0A76ED395');
        $this->addSql('DROP TABLE bank_connection');
        $this->addSql('ALTER TABLE account DROP CONSTRAINT FK_7D3656A487756E0A');
        $this->addSql('DROP INDEX IDX_7D3656A487756E0A');
        $this->addSql('ALTER TABLE account DROP bank_connection_id');
        $this->addSql('ALTER TABLE account RENAME COLUMN external_account_id TO bridge_account_id');
    }
}
