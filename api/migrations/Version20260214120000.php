<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20260214120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Rename task columns: name→title, done_date→completed_at; add Google Tasks tracking columns';
    }

    public function up(Schema $schema): void
    {
        // Part A: Rename Task fields
        $this->addSql('ALTER TABLE task RENAME COLUMN name TO title');
        $this->addSql('ALTER TABLE task RENAME COLUMN done_date TO completed_at');
        $this->addSql('DROP INDEX idx_task_done_date');
        $this->addSql('CREATE INDEX idx_task_completed_at ON task (completed_at)');

        // Part C: Google Tasks tracking columns on task table
        $this->addSql('ALTER TABLE task ADD google_task_id VARCHAR(1024) DEFAULT NULL');
        $this->addSql('ALTER TABLE task ADD google_task_list_id VARCHAR(255) DEFAULT NULL');
        $this->addSql('ALTER TABLE task ADD google_task_etag VARCHAR(255) DEFAULT NULL');
        $this->addSql('ALTER TABLE task ADD google_task_updated_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL');

        // Part C: Google Task List ID on user table
        $this->addSql('ALTER TABLE "user" ADD google_task_list_id VARCHAR(255) DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE "user" DROP COLUMN google_task_list_id');

        $this->addSql('ALTER TABLE task DROP COLUMN google_task_id');
        $this->addSql('ALTER TABLE task DROP COLUMN google_task_list_id');
        $this->addSql('ALTER TABLE task DROP COLUMN google_task_etag');
        $this->addSql('ALTER TABLE task DROP COLUMN google_task_updated_at');

        $this->addSql('DROP INDEX idx_task_completed_at');
        $this->addSql('CREATE INDEX idx_task_done_date ON task (done_date)');
        $this->addSql('ALTER TABLE task RENAME COLUMN title TO name');
        $this->addSql('ALTER TABLE task RENAME COLUMN completed_at TO done_date');
    }
}
