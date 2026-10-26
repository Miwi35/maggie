<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Where an event's location is (MAG-653): its geocoding, cached for the trips.
 */
final class Version20261026230000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add event.location_lat and event.location_lon';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE event ADD location_lat DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE event ADD location_lon DOUBLE PRECISION DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE event DROP location_lat');
        $this->addSql('ALTER TABLE event DROP location_lon');
    }
}
