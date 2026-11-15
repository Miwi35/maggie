<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** A period has no type, public holiday included (MAG-824): its meaning is in its name. The column is empty, no import ever ran. */
final class Version20261112210000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Drop period.holiday';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE period DROP holiday');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE period ADD holiday VARCHAR(10) DEFAULT NULL');
    }
}
