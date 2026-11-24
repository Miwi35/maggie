<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** The words a proaction was asked in (MAG-977): null for those already scheduled. */
final class Version20261123120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add origin to proaction';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE proaction ADD origin JSON DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE proaction DROP origin');
    }
}
