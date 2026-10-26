<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The places the user goes to (MAG-652): home and the usual destinations, with their parking.
 */
final class Version20261026210000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create place';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE place (id UUID NOT NULL, name VARCHAR(255) NOT NULL, address VARCHAR(500) NOT NULL, latitude DOUBLE PRECISION DEFAULT NULL, longitude DOUBLE PRECISION DEFAULT NULL, parking_address VARCHAR(500) DEFAULT NULL, parking_latitude DOUBLE PRECISION DEFAULT NULL, parking_longitude DOUBLE PRECISION DEFAULT NULL, is_home BOOLEAN DEFAULT false NOT NULL, created_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX idx_place_user ON place (user_id)');
        $this->addSql('ALTER TABLE place ADD CONSTRAINT FK_741D53CDA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE place DROP CONSTRAINT FK_741D53CDA76ED395');
        $this->addSql('DROP TABLE place');
    }
}
