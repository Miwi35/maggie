<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** Working hours as settings (MAG-790): start, end and ISO days, read by the agent for its tone. */
final class Version20261112090000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add the working hours settings to user_preference';
    }

    public function up(Schema $schema): void
    {
        $this->addSql("ALTER TABLE user_preference ADD work_start_time VARCHAR(5) DEFAULT '09:00' NOT NULL");
        $this->addSql("ALTER TABLE user_preference ADD work_end_time VARCHAR(5) DEFAULT '18:00' NOT NULL");
        $this->addSql("ALTER TABLE user_preference ADD work_days JSON DEFAULT '[1,2,3,4,5]' NOT NULL");
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE user_preference DROP work_start_time');
        $this->addSql('ALTER TABLE user_preference DROP work_end_time');
        $this->addSql('ALTER TABLE user_preference DROP work_days');
    }
}
