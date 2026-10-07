<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * What a product is bought in (MAG-292): « paquet de 500 g » is
 * (pack, 500, g). Three nullable columns and no data migration: an existing
 * product keeps no packaging, and its recipe unit is used as before.
 */
final class Version20261007090000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Let a product say what it is bought in: product.packaging_unit, packaging_size, packaging_size_unit';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE product ADD packaging_unit VARCHAR(20) DEFAULT NULL');
        $this->addSql('ALTER TABLE product ADD packaging_size DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE product ADD packaging_size_unit VARCHAR(20) DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE product DROP packaging_size_unit');
        $this->addSql('ALTER TABLE product DROP packaging_size');
        $this->addSql('ALTER TABLE product DROP packaging_unit');
    }
}
