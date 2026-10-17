<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Settled rejections (MAG-424): the owner says a rejected payment has been
 * dealt with, and the account's incidents leave it out of the pile.
 */
final class Version20261016180000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add rejection_settled_at to transaction';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction ADD rejection_settled_at TIMESTAMP(0) WITHOUT TIME ZONE DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction DROP rejection_settled_at');
    }
}
