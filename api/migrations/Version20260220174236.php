<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20260220174236 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create user_preference table';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE user_preference (id UUID NOT NULL, theme VARCHAR(10) DEFAULT \'system\' NOT NULL, locale VARCHAR(10) DEFAULT \'fr\' NOT NULL, timezone VARCHAR(50) DEFAULT \'Europe/Paris\' NOT NULL, default_calendar_view VARCHAR(10) DEFAULT \'month\' NOT NULL, enabled_agenda_ids JSON NOT NULL, notifications_enabled BOOLEAN DEFAULT true NOT NULL, created_at TIMESTAMP(0) WITHOUT TIME ZONE NOT NULL, updated_at TIMESTAMP(0) WITHOUT TIME ZONE NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX uniq_user_preference_user ON user_preference (user_id)');
        $this->addSql('ALTER TABLE user_preference ADD CONSTRAINT FK_FA0E76BFA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE user_preference DROP CONSTRAINT FK_FA0E76BFA76ED395');
        $this->addSql('DROP TABLE user_preference');
    }
}
