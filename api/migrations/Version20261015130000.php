<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The title the owner types on a meal's sheet (MAG-378). Null for every meal
 * already there: they keep being titled after their recipes.
 */
final class Version20261015130000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add the owner-typed custom title to meal';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE meal ADD custom_title VARCHAR(255) DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE meal DROP custom_title');
    }
}
