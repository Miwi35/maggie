<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The cushion's critical threshold and its share of the saving past it
 * (MAG-669): existing cushions take the defaults, 2 months and 60 %.
 */
final class Version20261027210000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add critical_months and cushion_share_percent to safety_cushion';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE safety_cushion ADD critical_months INT DEFAULT 2 NOT NULL');
        $this->addSql('ALTER TABLE safety_cushion ADD cushion_share_percent INT DEFAULT 60 NOT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE safety_cushion DROP critical_months');
        $this->addSql('ALTER TABLE safety_cushion DROP cushion_share_percent');
    }
}
