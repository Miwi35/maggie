<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** The programmation of a task (MAG-718): the day planned, its moment, the time and the reminders at a precise time. */
final class Version20261030070000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add scheduled_date, scheduled_moment, scheduled_time and reminders to task';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE task ADD scheduled_date DATE DEFAULT NULL');
        $this->addSql('ALTER TABLE task ADD scheduled_moment VARCHAR(20) DEFAULT NULL');
        $this->addSql('ALTER TABLE task ADD scheduled_time VARCHAR(5) DEFAULT NULL');
        $this->addSql("ALTER TABLE task ADD reminders JSON DEFAULT '[]' NOT NULL");
        $this->addSql('CREATE INDEX idx_task_scheduled_date ON task (scheduled_date)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('DROP INDEX idx_task_scheduled_date');
        $this->addSql('ALTER TABLE task DROP scheduled_date');
        $this->addSql('ALTER TABLE task DROP scheduled_moment');
        $this->addSql('ALTER TABLE task DROP scheduled_time');
        $this->addSql('ALTER TABLE task DROP reminders');
    }
}
