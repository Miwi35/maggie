<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The user's project scenarios (MAG-550): a selection and an order of projects,
 * simulated repayments, placements and a monthly saving. At most one is current
 * per user — the database says so, not only the handler.
 */
final class Version20261023090000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add scenario table';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE scenario (id UUID NOT NULL, name VARCHAR(120) NOT NULL, kind VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL, is_current BOOLEAN DEFAULT false NOT NULL, auto_key VARCHAR(16) DEFAULT NULL, projects JSON NOT NULL, repayments JSON NOT NULL, placements JSON NOT NULL, monthly_saving INT DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_3E45C8D8A76ED395 ON scenario (user_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_scenario_current_per_user ON scenario (user_id) WHERE (is_current = true)');
        $this->addSql('ALTER TABLE scenario ADD CONSTRAINT FK_3E45C8D8A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE scenario DROP CONSTRAINT FK_3E45C8D8A76ED395');
        $this->addSql('DROP TABLE scenario');
    }
}
