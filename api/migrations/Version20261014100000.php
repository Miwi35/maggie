<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * One line per product, the needs of every meal added up on it (MAG-380).
 *
 * `grocery_item.needed` keeps what the meals need of the product in base units
 * (« 800 g », or « 300 g + 2 pièces »), while `quantity` stays what to buy.
 * A meal can now put two needs of one product on a line — grams and pieces —
 * so its contribution is unique per unit as well. Lines already on the list
 * keep a null `needed` until a meal touches them or the consolidation command
 * (`maggie:grocery:consolidate-lines`) runs.
 */
final class Version20261014100000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add grocery_item.needed and make a meal\'s contribution unique per line and unit';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE grocery_item ADD needed JSON DEFAULT NULL');
        $this->addSql('DROP INDEX uniq_meal_grocery_contribution');
        $this->addSql('CREATE UNIQUE INDEX uniq_meal_grocery_contribution ON meal_grocery_contribution (meal_id, grocery_item_id, unit)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('DROP INDEX uniq_meal_grocery_contribution');
        $this->addSql('CREATE UNIQUE INDEX uniq_meal_grocery_contribution ON meal_grocery_contribution (meal_id, grocery_item_id)');
        $this->addSql('ALTER TABLE grocery_item DROP needed');
    }
}
