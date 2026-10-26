<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The owner's last position and the car's place (MAG-650): one row per user, never a history.
 */
final class Version20261026200000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create user_location';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE user_location (id UUID NOT NULL, user_id UUID NOT NULL, latitude DOUBLE PRECISION DEFAULT NULL, longitude DOUBLE PRECISION DEFAULT NULL, accuracy DOUBLE PRECISION DEFAULT NULL, captured_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, car_latitude DOUBLE PRECISION DEFAULT NULL, car_longitude DOUBLE PRECISION DEFAULT NULL, car_parked_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX UNIQ_BE136DCBA76ED395 ON user_location (user_id)');
        $this->addSql('ALTER TABLE user_location ADD CONSTRAINT FK_BE136DCBA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE user_location DROP CONSTRAINT FK_BE136DCBA76ED395');
        $this->addSql('DROP TABLE user_location');
    }
}
