<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Transaction sheet (MAG-464): a free note on the line, NULL for every
 * existing row. Additive only.
 */
final class Version20261014090000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add transaction.note';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction ADD note TEXT DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction DROP note');
    }
}
