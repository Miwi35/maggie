<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20260227202728 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Grocery list redesign: add Store entity, forever list, store-based organization';
    }

    public function up(Schema $schema): void
    {
        // Create store table
        $this->addSql('CREATE TABLE store (id UUID NOT NULL, name VARCHAR(255) NOT NULL, description TEXT DEFAULT NULL, visit_order INT NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_FF575877A76ED395 ON store (user_id)');
        $this->addSql('ALTER TABLE store ADD CONSTRAINT FK_FF575877A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');

        // Add store_id and buy_after to grocery_item
        $this->addSql('ALTER TABLE grocery_item ADD store_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE grocery_item ADD buy_after DATE DEFAULT NULL');
        $this->addSql('ALTER TABLE grocery_item ADD CONSTRAINT FK_8F9EDB8AB092A811 FOREIGN KEY (store_id) REFERENCES store (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_8F9EDB8AB092A811 ON grocery_item (store_id)');

        // Remove weekly concepts from grocery_list
        // Keep only the most recent list per user before adding unique constraint
        $this->addSql('DELETE FROM grocery_list WHERE id NOT IN (SELECT DISTINCT ON (user_id) id FROM grocery_list ORDER BY user_id, created_at DESC)');
        $this->addSql('ALTER TABLE grocery_list DROP COLUMN week_start');
        $this->addSql('ALTER TABLE grocery_list DROP COLUMN status');
        $this->addSql('DROP INDEX IF EXISTS idx_d44d068ca76ed395');
        $this->addSql('CREATE UNIQUE INDEX uniq_grocery_list_user ON grocery_list (user_id)');

        // Add preferred_store_id, fallback_store_id, shelf_life_days to product
        $this->addSql('ALTER TABLE product ADD preferred_store_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE product ADD fallback_store_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE product ADD shelf_life_days INT DEFAULT NULL');
        $this->addSql('ALTER TABLE product ADD CONSTRAINT FK_D34A04ADA654947E FOREIGN KEY (preferred_store_id) REFERENCES store (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('ALTER TABLE product ADD CONSTRAINT FK_D34A04AD6EC646BD FOREIGN KEY (fallback_store_id) REFERENCES store (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_D34A04ADA654947E ON product (preferred_store_id)');
        $this->addSql('CREATE INDEX IDX_D34A04AD6EC646BD ON product (fallback_store_id)');
    }

    public function down(Schema $schema): void
    {
        // Remove product store columns
        $this->addSql('ALTER TABLE product DROP CONSTRAINT FK_D34A04ADA654947E');
        $this->addSql('ALTER TABLE product DROP CONSTRAINT FK_D34A04AD6EC646BD');
        $this->addSql('DROP INDEX IDX_D34A04ADA654947E');
        $this->addSql('DROP INDEX IDX_D34A04AD6EC646BD');
        $this->addSql('ALTER TABLE product DROP preferred_store_id');
        $this->addSql('ALTER TABLE product DROP fallback_store_id');
        $this->addSql('ALTER TABLE product DROP shelf_life_days');

        // Restore grocery_list weekly columns
        $this->addSql('DROP INDEX uniq_grocery_list_user');
        $this->addSql('ALTER TABLE grocery_list ADD week_start DATE NOT NULL DEFAULT CURRENT_DATE');
        $this->addSql('ALTER TABLE grocery_list ADD status VARCHAR(20) DEFAULT \'draft\' NOT NULL');
        $this->addSql('CREATE INDEX idx_d44d068ca76ed395 ON grocery_list (user_id)');

        // Remove grocery_item store columns
        $this->addSql('ALTER TABLE grocery_item DROP CONSTRAINT FK_8F9EDB8AB092A811');
        $this->addSql('DROP INDEX IDX_8F9EDB8AB092A811');
        $this->addSql('ALTER TABLE grocery_item DROP store_id');
        $this->addSql('ALTER TABLE grocery_item DROP buy_after');

        // Drop store table
        $this->addSql('ALTER TABLE store DROP CONSTRAINT FK_FF575877A76ED395');
        $this->addSql('DROP TABLE store');
    }
}
