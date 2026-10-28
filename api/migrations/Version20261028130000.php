<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * `notification.expires_at` (MAG-679): when an alert stops being worth saying — a departure alert
 * at the start of its event. Null for every notification that exists: none of them expires.
 */
final class Version20261028130000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add notification.expires_at';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE notification ADD expires_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE notification DROP expires_at');
    }
}
