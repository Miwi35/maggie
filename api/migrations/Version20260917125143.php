<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20260917125143 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add the monthly review verdict on transaction';
    }

    public function up(Schema $schema): void
    {
        // Existing rows need a value before the column can be NOT NULL: nothing
        // has been reviewed yet.
        $this->addSql("ALTER TABLE transaction ADD retrospect VARCHAR(20) DEFAULT 'unrated' NOT NULL");
        $this->addSql('ALTER TABLE transaction ALTER COLUMN retrospect DROP DEFAULT');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction DROP retrospect');
    }
}
