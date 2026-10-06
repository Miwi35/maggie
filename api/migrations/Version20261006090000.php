<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** The city Maggie reads the weather for when none is given (MAG-156). */
final class Version20261006090000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add user_preference.default_city, the default city of the weather tool';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE user_preference ADD default_city VARCHAR(100) DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE user_preference DROP default_city');
    }
}
