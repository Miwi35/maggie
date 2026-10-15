<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The guests of a meal and the user's default (MAG-482). Every meal already
 * there names none, so it follows the default — 2 until the user sets one.
 */
final class Version20261015150000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add meal.guests and create meal_settings, with the default guests';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE meal ADD guests INT DEFAULT NULL');
        $this->addSql('CREATE TABLE meal_settings (id UUID NOT NULL, default_guests INT DEFAULT 2 NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX uniq_meal_settings_user ON meal_settings (user_id)');
        $this->addSql('ALTER TABLE meal_settings ADD CONSTRAINT FK_8157BBB7A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE meal_settings DROP CONSTRAINT FK_8157BBB7A76ED395');
        $this->addSql('DROP TABLE meal_settings');
        $this->addSql('ALTER TABLE meal DROP guests');
    }
}
