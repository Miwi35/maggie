<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * When a meal's ingredients were chosen for the grocery list (MAG-295).
 *
 * Additive and null for every existing meal: they keep feeding the list from
 * their recipes, as before, until their owner chooses.
 */
final class Version20261008200000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Meal: grocery_choice_made_at, null until the ingredients going on the list are chosen';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE meal ADD grocery_choice_made_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE meal DROP grocery_choice_made_at');
    }
}
