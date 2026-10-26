<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The vitamins, minerals and fatty acids of a product per 100 g, from Ciqual (MAG-648): the recipe sheet's micronutrients per serving.
 */
final class Version20261026220000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add product.micronutrients_per100g';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE product ADD micronutrients_per100g JSON DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE product DROP micronutrients_per100g');
    }
}
