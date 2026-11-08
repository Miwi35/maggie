<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** What was paid at the till for an errand, in cents, typed on finishing the store (MAG-765). */
final class Version20261109090000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add paid_amount_cents to errand';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE errand ADD paid_amount_cents INT DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE errand DROP paid_amount_cents');
    }
}
