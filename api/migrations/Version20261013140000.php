<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Treasury outlook (MAG-279): the overdraft the bank allows, in cents. 0 for
 * every existing row — any negative balance is an overdraft until the owner
 * says otherwise. Additive only.
 */
final class Version20261013140000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add treasury_settings.overdraft_limit_cents';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE treasury_settings ADD overdraft_limit_cents INT DEFAULT 0 NOT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE treasury_settings DROP overdraft_limit_cents');
    }
}
