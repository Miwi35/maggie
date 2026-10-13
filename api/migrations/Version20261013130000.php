<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * A task takes Google Tasks' fields: a day, a parent, an order, links (MAG-404).
 *
 * `task.due_date` becomes a day, as Google's API has it. Each existing
 * instant is turned into the day it falls on in its owner's time zone: the
 * midnight Europe/Paris create_task wrote is 22:00 UTC the day before, and a
 * plain cast would have moved every such task back a day. PostgreSQL refuses a
 * subquery in `ALTER … USING`, hence the column added, filled, then swapped.
 *
 * The tasks already synced are read again from Google on the next pull, so
 * the subtasks, the order and the links arrive for them too.
 */
final class Version20261013130000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Task: due_date as a day in the owner\'s time zone, parent_id, position, links';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE task ADD due_day DATE DEFAULT NULL');
        $this->addSql(<<<'SQL'
            UPDATE task SET due_day = (task.due_date AT TIME ZONE COALESCE(
                (SELECT p.timezone FROM user_preference p WHERE p.user_id = task.user_id),
                'Europe/Paris'
            ))::date
            WHERE task.due_date IS NOT NULL
            SQL);
        $this->addSql('DROP INDEX idx_task_due_date');
        $this->addSql('ALTER TABLE task DROP due_date');
        $this->addSql('ALTER TABLE task RENAME COLUMN due_day TO due_date');
        $this->addSql('CREATE INDEX idx_task_due_date ON task (due_date)');

        $this->addSql('ALTER TABLE task ADD parent_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE task ADD position VARCHAR(64) DEFAULT NULL');
        $this->addSql('ALTER TABLE task ADD links JSON DEFAULT \'[]\' NOT NULL');
        $this->addSql('ALTER TABLE task ADD CONSTRAINT FK_527EDB25727ACA70 FOREIGN KEY (parent_id) REFERENCES task (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_527EDB25727ACA70 ON task (parent_id)');

        // The pull skips a task Google has not changed since the last sync:
        // forgetting when it last did makes the next one read every task
        // again, and bring its parent, its place and its links.
        $this->addSql('UPDATE task SET google_task_updated_at = NULL WHERE google_task_id IS NOT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE task DROP CONSTRAINT FK_527EDB25727ACA70');
        $this->addSql('DROP INDEX IDX_527EDB25727ACA70');
        $this->addSql('ALTER TABLE task DROP parent_id');
        $this->addSql('ALTER TABLE task DROP position');
        $this->addSql('ALTER TABLE task DROP links');

        // Back to an instant: midnight of the day, in the owner's time zone.
        $this->addSql('ALTER TABLE task ADD due_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL');
        $this->addSql(<<<'SQL'
            UPDATE task SET due_at = task.due_date::timestamp AT TIME ZONE COALESCE(
                (SELECT p.timezone FROM user_preference p WHERE p.user_id = task.user_id),
                'Europe/Paris'
            )
            WHERE task.due_date IS NOT NULL
            SQL);
        $this->addSql('DROP INDEX idx_task_due_date');
        $this->addSql('ALTER TABLE task DROP due_date');
        $this->addSql('ALTER TABLE task RENAME COLUMN due_at TO due_date');
        $this->addSql('CREATE INDEX idx_task_due_date ON task (due_date)');
    }
}
