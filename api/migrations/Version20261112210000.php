<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** The evening of the Tasks screen is gone (MAG-825): its review, « what helped », goes with it. */
final class Version20261112210000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Drop the day_review table';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE day_review DROP CONSTRAINT FK_DE9D6A1BA76ED395');
        $this->addSql('DROP TABLE day_review');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('CREATE TABLE day_review (id UUID NOT NULL, date DATE NOT NULL, helped JSON DEFAULT \'[]\' NOT NULL, reviewed_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_DE9D6A1BA76ED395 ON day_review (user_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_day_review_user_date ON day_review (user_id, date)');
        $this->addSql('ALTER TABLE day_review ADD CONSTRAINT FK_DE9D6A1BA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
    }
}
