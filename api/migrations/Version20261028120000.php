<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The last GPS position and the last network position are kept apart (MAG-666). The one position a
 * row held becomes the GPS one when it was GPS-grade (50 m or better), the network one otherwise.
 */
final class Version20261028120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Split the user_location position into a GPS and a network position';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE user_location RENAME COLUMN latitude TO gps_latitude');
        $this->addSql('ALTER TABLE user_location RENAME COLUMN longitude TO gps_longitude');
        $this->addSql('ALTER TABLE user_location RENAME COLUMN accuracy TO gps_accuracy');
        $this->addSql('ALTER TABLE user_location RENAME COLUMN captured_at TO gps_captured_at');
        $this->addSql('ALTER TABLE user_location ADD network_latitude DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE user_location ADD network_longitude DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE user_location ADD network_accuracy DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE user_location ADD network_captured_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL');
        $this->addSql('UPDATE user_location SET network_latitude = gps_latitude, network_longitude = gps_longitude, network_accuracy = gps_accuracy, network_captured_at = gps_captured_at, gps_latitude = NULL, gps_longitude = NULL, gps_accuracy = NULL, gps_captured_at = NULL WHERE gps_accuracy IS NOT NULL AND (gps_accuracy <= 0 OR gps_accuracy > 50)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('UPDATE user_location SET gps_latitude = network_latitude, gps_longitude = network_longitude, gps_accuracy = network_accuracy, gps_captured_at = network_captured_at WHERE network_captured_at IS NOT NULL AND (gps_captured_at IS NULL OR network_captured_at > gps_captured_at)');
        $this->addSql('ALTER TABLE user_location DROP network_latitude');
        $this->addSql('ALTER TABLE user_location DROP network_longitude');
        $this->addSql('ALTER TABLE user_location DROP network_accuracy');
        $this->addSql('ALTER TABLE user_location DROP network_captured_at');
        $this->addSql('ALTER TABLE user_location RENAME COLUMN gps_latitude TO latitude');
        $this->addSql('ALTER TABLE user_location RENAME COLUMN gps_longitude TO longitude');
        $this->addSql('ALTER TABLE user_location RENAME COLUMN gps_accuracy TO accuracy');
        $this->addSql('ALTER TABLE user_location RENAME COLUMN gps_captured_at TO captured_at');
    }
}
