<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * A standard category carries a stable key (MAG-273).
 *
 * `category.standard_key` is how the reference salary finds « Salaire »
 * whatever the owner renamed it to, and keeps « Primes » out of it. One key
 * per user at most. Nothing is tagged here: the categories made by hand get
 * their key from `app:finance:categories:tag-standard`, which says what it
 * did.
 */
final class Version20261012180000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Give a standard category a stable key: category.standard_key, unique per user';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE category ADD standard_key VARCHAR(40) DEFAULT NULL');
        $this->addSql('CREATE UNIQUE INDEX uniq_category_user_standard_key ON category (user_id, standard_key)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('DROP INDEX uniq_category_user_standard_key');
        $this->addSql('ALTER TABLE category DROP standard_key');
    }
}
