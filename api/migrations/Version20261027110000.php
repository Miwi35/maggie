<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The trip Maggie chose to an event (MAG-654): one per event, removed with it.
 */
final class Version20261027110000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create trip';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE trip (id UUID NOT NULL, mode VARCHAR(10) NOT NULL, steps JSON NOT NULL, duration_minutes INT NOT NULL, depart_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, navigation_url TEXT NOT NULL, origin_kind VARCHAR(10) NOT NULL, origin_ref UUID DEFAULT NULL, origin_label VARCHAR(255) NOT NULL, origin_latitude DOUBLE PRECISION NOT NULL, origin_longitude DOUBLE PRECISION NOT NULL, car_latitude DOUBLE PRECISION DEFAULT NULL, car_longitude DOUBLE PRECISION DEFAULT NULL, origin_decided_by VARCHAR(10) NOT NULL, computed_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, event_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX UNIQ_7656F53B71F7E88B ON trip (event_id)');
        $this->addSql('CREATE INDEX idx_trip_depart_at ON trip (depart_at)');
        $this->addSql('ALTER TABLE trip ADD CONSTRAINT FK_7656F53B71F7E88B FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE trip DROP CONSTRAINT FK_7656F53B71F7E88B');
        $this->addSql('DROP TABLE trip');
    }
}
