<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20260219142625 extends AbstractMigration
{
    public function getDescription(): string
    {
        return '';
    }

    public function up(Schema $schema): void
    {
        // this up() migration is auto-generated, please modify it to your needs
        $this->addSql('CREATE TABLE grocery_item (id UUID NOT NULL, custom_label VARCHAR(255) DEFAULT NULL, quantity DOUBLE PRECISION DEFAULT NULL, unit VARCHAR(20) DEFAULT NULL, checked BOOLEAN DEFAULT false NOT NULL, source VARCHAR(20) NOT NULL, grocery_list_id UUID NOT NULL, product_id UUID DEFAULT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_8F9EDB8AD059BDAB ON grocery_item (grocery_list_id)');
        $this->addSql('CREATE INDEX IDX_8F9EDB8A4584665A ON grocery_item (product_id)');
        $this->addSql('CREATE TABLE grocery_list (id UUID NOT NULL, week_start DATE NOT NULL, status VARCHAR(20) DEFAULT \'draft\' NOT NULL, created_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, updated_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_D44D068CA76ED395 ON grocery_list (user_id)');
        $this->addSql('CREATE TABLE meal (slot VARCHAR(10) NOT NULL, id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE TABLE meal_recipe (meal_id UUID NOT NULL, recipe_id UUID NOT NULL, PRIMARY KEY (meal_id, recipe_id))');
        $this->addSql('CREATE INDEX IDX_B5820C3E639666D6 ON meal_recipe (meal_id)');
        $this->addSql('CREATE INDEX IDX_B5820C3E59D8A214 ON meal_recipe (recipe_id)');
        $this->addSql('CREATE TABLE product (id UUID NOT NULL, name VARCHAR(255) NOT NULL, default_unit VARCHAR(20) DEFAULT NULL, category VARCHAR(20) NOT NULL, user_id UUID NOT NULL, dtype VARCHAR(20) NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_D34A04ADA76ED395 ON product (user_id)');
        $this->addSql('CREATE TABLE recipe (id UUID NOT NULL, name VARCHAR(255) NOT NULL, servings INT NOT NULL, tags JSON NOT NULL, notes TEXT DEFAULT NULL, created_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, updated_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_DA88B137A76ED395 ON recipe (user_id)');
        $this->addSql('CREATE TABLE recipe_ingredient (id UUID NOT NULL, quantity DOUBLE PRECISION NOT NULL, unit VARCHAR(20) NOT NULL, recipe_id UUID NOT NULL, ingredient_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_22D1FE1359D8A214 ON recipe_ingredient (recipe_id)');
        $this->addSql('CREATE INDEX IDX_22D1FE13933FE08C ON recipe_ingredient (ingredient_id)');
        $this->addSql('CREATE TABLE recurring_grocery_item (id UUID NOT NULL, custom_label VARCHAR(255) DEFAULT NULL, quantity DOUBLE PRECISION DEFAULT NULL, unit VARCHAR(20) DEFAULT NULL, frequency VARCHAR(20) NOT NULL, product_id UUID DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_415E588A4584665A ON recurring_grocery_item (product_id)');
        $this->addSql('CREATE INDEX IDX_415E588AA76ED395 ON recurring_grocery_item (user_id)');
        $this->addSql('ALTER TABLE grocery_item ADD CONSTRAINT FK_8F9EDB8AD059BDAB FOREIGN KEY (grocery_list_id) REFERENCES grocery_list (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE grocery_item ADD CONSTRAINT FK_8F9EDB8A4584665A FOREIGN KEY (product_id) REFERENCES product (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE grocery_list ADD CONSTRAINT FK_D44D068CA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE meal ADD CONSTRAINT FK_9EF68E9CBF396750 FOREIGN KEY (id) REFERENCES event (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE meal_recipe ADD CONSTRAINT FK_B5820C3E639666D6 FOREIGN KEY (meal_id) REFERENCES meal (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE meal_recipe ADD CONSTRAINT FK_B5820C3E59D8A214 FOREIGN KEY (recipe_id) REFERENCES recipe (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE product ADD CONSTRAINT FK_D34A04ADA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE recipe ADD CONSTRAINT FK_DA88B137A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE recipe_ingredient ADD CONSTRAINT FK_22D1FE1359D8A214 FOREIGN KEY (recipe_id) REFERENCES recipe (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE recipe_ingredient ADD CONSTRAINT FK_22D1FE13933FE08C FOREIGN KEY (ingredient_id) REFERENCES product (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE recurring_grocery_item ADD CONSTRAINT FK_415E588A4584665A FOREIGN KEY (product_id) REFERENCES product (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE recurring_grocery_item ADD CONSTRAINT FK_415E588AA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE event ADD dtype VARCHAR(20) DEFAULT \'event\' NOT NULL');
    }

    public function down(Schema $schema): void
    {
        // this down() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE grocery_item DROP CONSTRAINT FK_8F9EDB8AD059BDAB');
        $this->addSql('ALTER TABLE grocery_item DROP CONSTRAINT FK_8F9EDB8A4584665A');
        $this->addSql('ALTER TABLE grocery_list DROP CONSTRAINT FK_D44D068CA76ED395');
        $this->addSql('ALTER TABLE meal DROP CONSTRAINT FK_9EF68E9CBF396750');
        $this->addSql('ALTER TABLE meal_recipe DROP CONSTRAINT FK_B5820C3E639666D6');
        $this->addSql('ALTER TABLE meal_recipe DROP CONSTRAINT FK_B5820C3E59D8A214');
        $this->addSql('ALTER TABLE product DROP CONSTRAINT FK_D34A04ADA76ED395');
        $this->addSql('ALTER TABLE recipe DROP CONSTRAINT FK_DA88B137A76ED395');
        $this->addSql('ALTER TABLE recipe_ingredient DROP CONSTRAINT FK_22D1FE1359D8A214');
        $this->addSql('ALTER TABLE recipe_ingredient DROP CONSTRAINT FK_22D1FE13933FE08C');
        $this->addSql('ALTER TABLE recurring_grocery_item DROP CONSTRAINT FK_415E588A4584665A');
        $this->addSql('ALTER TABLE recurring_grocery_item DROP CONSTRAINT FK_415E588AA76ED395');
        $this->addSql('DROP TABLE grocery_item');
        $this->addSql('DROP TABLE grocery_list');
        $this->addSql('DROP TABLE meal');
        $this->addSql('DROP TABLE meal_recipe');
        $this->addSql('DROP TABLE product');
        $this->addSql('DROP TABLE recipe');
        $this->addSql('DROP TABLE recipe_ingredient');
        $this->addSql('DROP TABLE recurring_grocery_item');
        $this->addSql('ALTER TABLE event DROP dtype');
    }
}
