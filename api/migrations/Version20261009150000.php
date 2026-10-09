<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The provider's account identification can be longer than the 128 characters
 * `account.external_key` allowed: the merge of duplicate accounts failed on it
 * in production (MAG-377). Text takes any length; the index stays valid.
 */
final class Version20261009150000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Widen account.external_key to TEXT';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE account ALTER external_key TYPE TEXT');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE account ALTER external_key TYPE VARCHAR(128)');
    }
}
