<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * A trip serves one occurrence of its event (MAG-862): one trip per event, leg and occurrence.
 * The trips kept so far served the start of their event, midnight in Paris for a whole-day one.
 */
final class Version20261119120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add occurrence_start_at to trip, one trip per event, leg and occurrence';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE trip ADD occurrence_start_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL');
        $this->addSql("UPDATE trip t SET occurrence_start_at = COALESCE(e.start_at, e.start_date::timestamp AT TIME ZONE 'Europe/Paris') FROM event e WHERE e.id = t.event_id");
        $this->addSql('ALTER TABLE trip ALTER occurrence_start_at SET NOT NULL');
        $this->addSql('DROP INDEX uniq_trip_event_leg');
        $this->addSql('CREATE UNIQUE INDEX uniq_trip_event_leg_occurrence ON trip (event_id, leg, occurrence_start_at)');
    }

    public function down(Schema $schema): void
    {
        // Back to one trip per event and leg: the earliest occurrence's stays.
        $this->addSql('DELETE FROM trip t USING trip earlier WHERE earlier.event_id = t.event_id AND earlier.leg = t.leg AND earlier.occurrence_start_at < t.occurrence_start_at');
        $this->addSql('DROP INDEX uniq_trip_event_leg_occurrence');
        $this->addSql('CREATE UNIQUE INDEX uniq_trip_event_leg ON trip (event_id, leg)');
        $this->addSql('ALTER TABLE trip DROP occurrence_start_at');
    }
}
