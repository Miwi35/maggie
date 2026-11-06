<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** The arrival the user gave for a whole-day event, a stay (MAG-733). */
final class Version20261106100000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add arrive_at to trip';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE trip ADD arrive_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE trip DROP arrive_at');
    }
}
