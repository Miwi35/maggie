<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20260227223000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Drop agent_message from shared DB (moved to agent-owned maggie_agent database)';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('DROP TABLE IF EXISTS agent_message');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('CREATE TABLE agent_message (id VARCHAR(26) NOT NULL, user_id VARCHAR(36) NOT NULL, role VARCHAR(20) NOT NULL, content TEXT NOT NULL, created_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX idx_agent_message_user_created ON agent_message (user_id, created_at)');
    }
}
