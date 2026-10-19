<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * What a palier celebrates (MAG-509): the title of the chapter, kept on the
 * reward so the gold banner reads it from Mercure and from the REST read alike.
 */
final class Version20261019230000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add title to reward';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE reward ADD title VARCHAR(255) DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE reward DROP title');
    }
}
