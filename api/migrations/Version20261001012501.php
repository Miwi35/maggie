<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20261001012501 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Record what each meal put on the grocery list, and when a recurring item was last added';
    }

    public function up(Schema $schema): void
    {
        $this->addSql(<<<'SQL'
            CREATE TABLE meal_grocery_contribution (
              id UUID NOT NULL,
              quantity DOUBLE PRECISION NOT NULL,
              unit VARCHAR(20) DEFAULT NULL,
              meal_id UUID NOT NULL,
              grocery_item_id UUID NOT NULL,
              PRIMARY KEY (id)
            )
        SQL);
        $this->addSql('CREATE INDEX IDX_8DB79A78639666D6 ON meal_grocery_contribution (meal_id)');
        $this->addSql('CREATE INDEX IDX_8DB79A78FF98F97E ON meal_grocery_contribution (grocery_item_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_meal_grocery_contribution ON meal_grocery_contribution (meal_id, grocery_item_id)');
        $this->addSql('ALTER TABLE meal_grocery_contribution ADD CONSTRAINT FK_8DB79A78639666D6 FOREIGN KEY (meal_id) REFERENCES meal (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE meal_grocery_contribution ADD CONSTRAINT FK_8DB79A78FF98F97E FOREIGN KEY (grocery_item_id) REFERENCES grocery_item (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE recurring_grocery_item ADD last_added_at DATE DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE meal_grocery_contribution DROP CONSTRAINT FK_8DB79A78639666D6');
        $this->addSql('ALTER TABLE meal_grocery_contribution DROP CONSTRAINT FK_8DB79A78FF98F97E');
        $this->addSql('DROP TABLE meal_grocery_contribution');
        $this->addSql('ALTER TABLE recurring_grocery_item DROP last_added_at');
    }
}
