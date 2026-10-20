<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Routines (MAG-511): a habit anchored to a moment of the day or an hour,
 * its recurrence held as JSON, and one tick per routine and local date.
 */
final class Version20261020190000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create routine and routine_check';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE routine (id UUID NOT NULL, title VARCHAR(255) NOT NULL, anchor VARCHAR(20) NOT NULL, anchor_time VARCHAR(5) DEFAULT NULL, recurrence JSON NOT NULL, active BOOLEAN DEFAULT true NOT NULL, remind BOOLEAN DEFAULT false NOT NULL, created_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX idx_routine_user ON routine (user_id)');
        $this->addSql('ALTER TABLE routine ADD CONSTRAINT FK_4BF6D8D6A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('CREATE TABLE routine_check (id UUID NOT NULL, date DATE NOT NULL, routine_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX uniq_routine_check_routine_date ON routine_check (routine_id, date)');
        $this->addSql('ALTER TABLE routine_check ADD CONSTRAINT FK_1496A07FF27A94C7 FOREIGN KEY (routine_id) REFERENCES routine (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE routine_check DROP CONSTRAINT FK_1496A07FF27A94C7');
        $this->addSql('ALTER TABLE routine DROP CONSTRAINT FK_4BF6D8D6A76ED395');
        $this->addSql('DROP TABLE routine_check');
        $this->addSql('DROP TABLE routine');
    }
}
