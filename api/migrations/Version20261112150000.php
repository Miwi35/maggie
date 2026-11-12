<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** The sick state (MAG-810): the day the user fell sick, null when they are not. */
final class Version20261112150000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add user_preference.sick_since';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE user_preference ADD sick_since DATE DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE user_preference DROP sick_since');
    }
}
