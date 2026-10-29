<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** Quiet hours of the user (MAG-687): chat pushes are silent in this window. */
final class Version20261029100000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add quiet_hours_start and quiet_hours_end to user_preference';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE user_preference ADD quiet_hours_start VARCHAR(5) DEFAULT NULL');
        $this->addSql('ALTER TABLE user_preference ADD quiet_hours_end VARCHAR(5) DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE user_preference DROP quiet_hours_start');
        $this->addSql('ALTER TABLE user_preference DROP quiet_hours_end');
    }
}
