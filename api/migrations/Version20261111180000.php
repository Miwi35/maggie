<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** The morning digest and the evening review as settings (MAG-787): on or off, and their hour. */
final class Version20261111180000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add the morning digest and evening review settings to user_preference';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE user_preference ADD morning_digest_enabled BOOLEAN DEFAULT true NOT NULL');
        $this->addSql("ALTER TABLE user_preference ADD morning_digest_time VARCHAR(5) DEFAULT '07:00' NOT NULL");
        $this->addSql('ALTER TABLE user_preference ADD evening_review_enabled BOOLEAN DEFAULT true NOT NULL');
        $this->addSql("ALTER TABLE user_preference ADD evening_review_time VARCHAR(5) DEFAULT '21:30' NOT NULL");
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE user_preference DROP morning_digest_enabled');
        $this->addSql('ALTER TABLE user_preference DROP morning_digest_time');
        $this->addSql('ALTER TABLE user_preference DROP evening_review_enabled');
        $this->addSql('ALTER TABLE user_preference DROP evening_review_time');
    }
}
