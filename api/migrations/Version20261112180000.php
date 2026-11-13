<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Google Tasks' `due` is the day a task is planned, not its deadline
 * (MAG-822): the sync now reads it as the scheduled day. A synced task that
 * only has a due day got it from Google, so that day becomes its scheduled
 * day and the deadline is emptied. A task with both days is left as is:
 * Google catches up on the next change of its scheduled day.
 *
 * Elasticsearch is reindexed on deploy.
 */
final class Version20261112180000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Task: a synced task\'s Google due day becomes its scheduled day';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('UPDATE task SET scheduled_date = due_date, due_date = NULL WHERE google_task_id IS NOT NULL AND due_date IS NOT NULL AND scheduled_date IS NULL');
    }

    public function down(Schema $schema): void
    {
        // Nothing to undo: once moved, these rows can no longer be told apart
        // from the tasks scheduled in Maggie.
    }
}
