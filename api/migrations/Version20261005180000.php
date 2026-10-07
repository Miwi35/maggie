<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * A reminder is about one occurrence, not about a series (MAG-121).
 *
 * `notification.occurrence_start_at` is what the reminder cron dedupes on from
 * now on. A recurring event is a single row whose `start_at` is its first
 * occurrence, so (event, minutes) named the whole series: the owner was reminded
 * of the first standup and of none of the ones after it.
 *
 * Nothing is backfilled, and that is a choice rather than an oversight. The
 * event an old reminder points at is named by an IRI holding a base32 ULID while
 * the column holds a UUID, so matching the two in SQL would mean re-encoding
 * every id — for rows whose occurrence we would still be guessing. The rows that
 * carry no occurrence are only the reminders already sent for events starting in
 * the next 24 hours, and the one thing their NULL can cost is one repeated
 * notification for those, once, right after the deploy.
 */
final class Version20261005180000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Dedupe event reminders per occurrence: notification.occurrence_start_at';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE notification ADD occurrence_start_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE notification DROP occurrence_start_at');
    }
}
