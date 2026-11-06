<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** When the cron last re-checked a trip before its departure (MAG-730). */
final class Version20261106090000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add last_checked_at to trip';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE trip ADD last_checked_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE trip DROP last_checked_at');
    }
}
