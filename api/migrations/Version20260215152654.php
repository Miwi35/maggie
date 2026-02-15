<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20260215152654 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create proaction table for autonomous scheduled agent tasks';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE proaction (id UUID NOT NULL, scheduled_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, prompt TEXT NOT NULL, status VARCHAR(20) NOT NULL, response TEXT DEFAULT NULL, error TEXT DEFAULT NULL, created_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, completed_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_732F50ABA76ED395 ON proaction (user_id)');
        $this->addSql('CREATE INDEX idx_proaction_schedule ON proaction (scheduled_at, status)');
        $this->addSql('CREATE INDEX idx_proaction_status ON proaction (status)');
        $this->addSql('ALTER TABLE proaction ADD CONSTRAINT FK_732F50ABA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE proaction DROP CONSTRAINT FK_732F50ABA76ED395');
        $this->addSql('DROP TABLE proaction');
    }
}
