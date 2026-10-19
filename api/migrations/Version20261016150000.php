<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** Reconnues à contrôler (MAG-460): when the owner checked a recognised line at the review. */
final class Version20261016150000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add controlled_at to transaction';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction ADD controlled_at TIMESTAMP(0) WITHOUT TIME ZONE DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction DROP controlled_at');
    }
}
