<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The quantity wanted of a packaged product, apart from its packagings (MAG-573).
 */
final class Version20261022170000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add grocery_item.desired_quantity and desired_unit';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE grocery_item ADD desired_quantity DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE grocery_item ADD desired_unit VARCHAR(20) DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE grocery_item DROP desired_quantity');
        $this->addSql('ALTER TABLE grocery_item DROP desired_unit');
    }
}
