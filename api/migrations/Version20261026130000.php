<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * What one piece of a product weighs (MAG-647): a recipe line counted in pieces reaches the nutrition through it.
 */
final class Version20261026130000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add product.piece_weight_grams';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE product ADD piece_weight_grams DOUBLE PRECISION DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE product DROP piece_weight_grams');
    }
}
