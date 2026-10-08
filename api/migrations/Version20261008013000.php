<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * What is left of a product at home (MAG-293).
 *
 * Additive, with the defaults in the database: every existing product is
 * « En stock », with no restock quantity and no automatic restock.
 */
final class Version20261008013000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Product stock: stock_state (default in_stock), restock_quantity, is_auto_restock (default false)';
    }

    public function up(Schema $schema): void
    {
        $this->addSql("ALTER TABLE product ADD stock_state VARCHAR(20) DEFAULT 'in_stock' NOT NULL");
        $this->addSql('ALTER TABLE product ADD restock_quantity INT DEFAULT NULL');
        $this->addSql('ALTER TABLE product ADD is_auto_restock BOOLEAN DEFAULT false NOT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE product DROP is_auto_restock');
        $this->addSql('ALTER TABLE product DROP restock_quantity');
        $this->addSql('ALTER TABLE product DROP stock_state');
    }
}
