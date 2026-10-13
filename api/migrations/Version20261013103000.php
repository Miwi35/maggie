<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * A proaction is an event of the user's internal « Maggie » agenda (ADR-012,
 * MAG-326): `proaction` holds what an event lacks — the prompt the agent runs at
 * the start, and how the run went. Joined to `event` like `meal`.
 */
final class Version20261013103000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'File proactions as events of the internal « Maggie » agenda';
    }

    public function up(Schema $schema): void
    {
        $this->addSql("CREATE TABLE proaction (prompt TEXT NOT NULL, execution_status VARCHAR(20) DEFAULT 'pending' NOT NULL, response TEXT DEFAULT NULL, error TEXT DEFAULT NULL, completed_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, id UUID NOT NULL, PRIMARY KEY (id))");
        $this->addSql('CREATE INDEX idx_proaction_execution_status ON proaction (execution_status)');
        $this->addSql('ALTER TABLE proaction ADD CONSTRAINT FK_732F50ABBF396750 FOREIGN KEY (id) REFERENCES event (id) ON DELETE CASCADE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql("DELETE FROM event WHERE dtype = 'proaction'");
        $this->addSql('DROP TABLE proaction');
    }
}
