<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** Where the user said the car is for a drive (MAG-929), kept for the recheck: null when unsaid. */
final class Version20261121120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add car_at to trip';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE trip ADD car_at VARCHAR(10) DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE trip DROP car_at');
    }
}
