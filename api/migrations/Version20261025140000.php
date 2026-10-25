<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Where the user stands in an activity in progress (MAG-613): one row per user
 * and activity, so it resumes on any device.
 */
final class Version20261025140000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add activity_status';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE activity_status (id UUID NOT NULL, activity VARCHAR(20) NOT NULL, origin_kind VARCHAR(30) NOT NULL, origin_id VARCHAR(64) NOT NULL, state TEXT NOT NULL, started_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, updated_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_2976851CA76ED395 ON activity_status (user_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_activity_status_user_activity ON activity_status (user_id, activity)');
        $this->addSql('ALTER TABLE activity_status ADD CONSTRAINT FK_2976851CA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE activity_status DROP CONSTRAINT FK_2976851CA76ED395');
        $this->addSql('DROP TABLE activity_status');
    }
}
