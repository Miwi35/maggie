<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The evening (MAG-500): one review per user and local date, what helped held
 * as JSON, and the evening time it opens after, 21:00 for every user already there.
 */
final class Version20261016130000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create day_review, add evening_time to task_settings';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE day_review (id UUID NOT NULL, date DATE NOT NULL, helped JSON DEFAULT \'[]\' NOT NULL, reviewed_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_DE9D6A1BA76ED395 ON day_review (user_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_day_review_user_date ON day_review (user_id, date)');
        $this->addSql('ALTER TABLE day_review ADD CONSTRAINT FK_DE9D6A1BA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE task_settings ADD evening_time VARCHAR(5) DEFAULT \'21:00\' NOT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE task_settings DROP evening_time');
        $this->addSql('ALTER TABLE day_review DROP CONSTRAINT FK_DE9D6A1BA76ED395');
        $this->addSql('DROP TABLE day_review');
    }
}
