<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** A task done with a timer, for its estimated minutes (MAG-766). */
final class Version20261109120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add timed to task';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE task ADD timed BOOLEAN DEFAULT false NOT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE task DROP timed');
    }
}
