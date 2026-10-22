<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The Finance Dashboard's read model (MAG-545): one row per user holding the
 * answer of the dashboard for the month it was computed.
 */
final class Version20261022100000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create finance_dashboard_snapshot';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE finance_dashboard_snapshot (id UUID NOT NULL, year SMALLINT DEFAULT 0 NOT NULL, month SMALLINT DEFAULT 0 NOT NULL, computed_on DATE DEFAULT NULL, payload TEXT DEFAULT NULL, stale BOOLEAN DEFAULT true NOT NULL, version INT DEFAULT 1 NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX uniq_finance_dashboard_snapshot_user ON finance_dashboard_snapshot (user_id)');
        $this->addSql('ALTER TABLE finance_dashboard_snapshot ADD CONSTRAINT FK_F9EFD3AFA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE finance_dashboard_snapshot DROP CONSTRAINT FK_F9EFD3AFA76ED395');
        $this->addSql('DROP TABLE finance_dashboard_snapshot');
    }
}
