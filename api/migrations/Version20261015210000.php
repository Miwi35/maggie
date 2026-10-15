<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * A catalogue of utensils per user, and what each recipe is made with
 * (MAG-489). Deleting a utensil or a recipe drops their links, never the other.
 */
final class Version20261015210000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add the utensil catalogue and the recipe_utensil links';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE utensil (id UUID NOT NULL, name VARCHAR(255) NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_9F283CBCA76ED395 ON utensil (user_id)');
        $this->addSql('ALTER TABLE utensil ADD CONSTRAINT FK_9F283CBCA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('CREATE TABLE recipe_utensil (recipe_id UUID NOT NULL, utensil_id UUID NOT NULL, PRIMARY KEY (recipe_id, utensil_id))');
        $this->addSql('CREATE INDEX IDX_D3CC32FC59D8A214 ON recipe_utensil (recipe_id)');
        $this->addSql('CREATE INDEX IDX_D3CC32FCEC4313DE ON recipe_utensil (utensil_id)');
        $this->addSql('ALTER TABLE recipe_utensil ADD CONSTRAINT FK_D3CC32FC59D8A214 FOREIGN KEY (recipe_id) REFERENCES recipe (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE recipe_utensil ADD CONSTRAINT FK_D3CC32FCEC4313DE FOREIGN KEY (utensil_id) REFERENCES utensil (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE recipe_utensil DROP CONSTRAINT FK_D3CC32FC59D8A214');
        $this->addSql('ALTER TABLE recipe_utensil DROP CONSTRAINT FK_D3CC32FCEC4313DE');
        $this->addSql('DROP TABLE recipe_utensil');
        $this->addSql('ALTER TABLE utensil DROP CONSTRAINT FK_9F283CBCA76ED395');
        $this->addSql('DROP TABLE utensil');
    }
}
