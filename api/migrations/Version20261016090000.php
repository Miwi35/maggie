<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The steps of a recipe (MAG-490), with the ingredients and utensils each one
 * uses and the earlier steps it waits for. Deleting a recipe drops its steps;
 * deleting a step, an ingredient or a utensil drops their links.
 */
final class Version20261016090000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add the recipe steps and their ingredient, utensil and wait links';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE recipe_step (id UUID NOT NULL, position INT NOT NULL, text TEXT NOT NULL, duration_minutes INT DEFAULT NULL, background BOOLEAN NOT NULL, recipe_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_3CA2A4E359D8A214 ON recipe_step (recipe_id)');
        $this->addSql('CREATE UNIQUE INDEX recipe_step_position ON recipe_step (recipe_id, position)');
        $this->addSql('ALTER TABLE recipe_step ADD CONSTRAINT FK_3CA2A4E359D8A214 FOREIGN KEY (recipe_id) REFERENCES recipe (id) ON DELETE CASCADE NOT DEFERRABLE');

        $this->addSql('CREATE TABLE recipe_step_ingredient (recipe_step_id UUID NOT NULL, ingredient_id UUID NOT NULL, PRIMARY KEY (recipe_step_id, ingredient_id))');
        $this->addSql('CREATE INDEX IDX_EB00B5D3F5610DC ON recipe_step_ingredient (recipe_step_id)');
        $this->addSql('CREATE INDEX IDX_EB00B5D933FE08C ON recipe_step_ingredient (ingredient_id)');
        $this->addSql('ALTER TABLE recipe_step_ingredient ADD CONSTRAINT FK_EB00B5D3F5610DC FOREIGN KEY (recipe_step_id) REFERENCES recipe_step (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE recipe_step_ingredient ADD CONSTRAINT FK_EB00B5D933FE08C FOREIGN KEY (ingredient_id) REFERENCES product (id) ON DELETE CASCADE NOT DEFERRABLE');

        $this->addSql('CREATE TABLE recipe_step_utensil (recipe_step_id UUID NOT NULL, utensil_id UUID NOT NULL, PRIMARY KEY (recipe_step_id, utensil_id))');
        $this->addSql('CREATE INDEX IDX_65DEED5C3F5610DC ON recipe_step_utensil (recipe_step_id)');
        $this->addSql('CREATE INDEX IDX_65DEED5CEC4313DE ON recipe_step_utensil (utensil_id)');
        $this->addSql('ALTER TABLE recipe_step_utensil ADD CONSTRAINT FK_65DEED5C3F5610DC FOREIGN KEY (recipe_step_id) REFERENCES recipe_step (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE recipe_step_utensil ADD CONSTRAINT FK_65DEED5CEC4313DE FOREIGN KEY (utensil_id) REFERENCES utensil (id) ON DELETE CASCADE NOT DEFERRABLE');

        $this->addSql('CREATE TABLE recipe_step_wait (recipe_step_id UUID NOT NULL, waits_for_id UUID NOT NULL, PRIMARY KEY (recipe_step_id, waits_for_id))');
        $this->addSql('CREATE INDEX IDX_24B930523F5610DC ON recipe_step_wait (recipe_step_id)');
        $this->addSql('CREATE INDEX IDX_24B93052505B2D4C ON recipe_step_wait (waits_for_id)');
        $this->addSql('ALTER TABLE recipe_step_wait ADD CONSTRAINT FK_24B930523F5610DC FOREIGN KEY (recipe_step_id) REFERENCES recipe_step (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE recipe_step_wait ADD CONSTRAINT FK_24B93052505B2D4C FOREIGN KEY (waits_for_id) REFERENCES recipe_step (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('DROP TABLE recipe_step_wait');
        $this->addSql('DROP TABLE recipe_step_utensil');
        $this->addSql('DROP TABLE recipe_step_ingredient');
        $this->addSql('DROP TABLE recipe_step');
    }
}
