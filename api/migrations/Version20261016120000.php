<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * « Pas besoin » on a « tu en prends souvent » suggestion (MAG-495): the
 * product is not offered again before this day.
 */
final class Version20261016120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add product.suggestion_snoozed_until, the day a dismissed purchase suggestion comes back';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE product ADD suggestion_snoozed_until DATE DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE product DROP suggestion_snoozed_until');
    }
}
