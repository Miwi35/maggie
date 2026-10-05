<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * A category can be a rente (MAG-46).
 *
 * `category.is_passive_income` is what tells a rent received from a salary —
 * both are income — and it is the only thing the independence counter counts.
 * Nothing is flagged by this migration: declaring which income is passive is
 * the user's call, not a guess made on a category's name.
 */
final class Version20261006100000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Let a category be declared a rente: category.is_passive_income';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE category ADD is_passive_income BOOLEAN DEFAULT false NOT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE category DROP is_passive_income');
    }
}
