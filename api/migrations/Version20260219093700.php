<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20260219093700 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Drop proaction table';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('DROP TABLE IF EXISTS proaction');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('CREATE TABLE proaction (id UUID NOT NULL, scheduled_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, prompt TEXT NOT NULL, status VARCHAR(20) NOT NULL, response TEXT DEFAULT NULL, error TEXT DEFAULT NULL, created_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, completed_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX idx_proaction_status ON proaction (status)');
        $this->addSql('CREATE INDEX idx_proaction_schedule ON proaction (scheduled_at, status)');
        $this->addSql('CREATE INDEX idx_732f50aba76ed395 ON proaction (user_id)');
        $this->addSql('ALTER TABLE proaction ADD CONSTRAINT fk_732f50aba76ed395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE INITIALLY IMMEDIATE');
    }
}
