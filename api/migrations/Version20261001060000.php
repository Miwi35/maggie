<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * One Google calendar is one agenda (MAG-148).
 *
 * Two agendas on the same `google_calendar_id` each synced on their own and
 * duplicated every event. `app:calendar:dedupe-google-agendas` merges the
 * duplicates still in the database — it runs just before this migration during
 * a deploy, because the index below cannot be created while one is left.
 */
final class Version20261001060000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Keep a user from connecting the same Google calendar twice';
    }

    public function up(Schema $schema): void
    {
        // Postgres counts every NULL as distinct, so the agendas with no Google
        // calendar — the common case — do not collide with each other. Same
        // shape as uniq_google_event_agenda on the event table.
        $this->addSql('CREATE UNIQUE INDEX uniq_agenda_user_google_calendar ON agenda (user_id, google_calendar_id)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('DROP INDEX uniq_agenda_user_google_calendar');
    }
}
