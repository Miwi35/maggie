<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20260214000303 extends AbstractMigration
{
    public function getDescription(): string
    {
        return '';
    }

    public function up(Schema $schema): void
    {
        // this up() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE "user" ADD google_access_token TEXT DEFAULT NULL');
        $this->addSql('ALTER TABLE "user" ADD google_refresh_token TEXT DEFAULT NULL');
        $this->addSql('ALTER TABLE "user" ADD google_token_expires_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL');
        $this->addSql('ALTER TABLE agenda ADD google_calendar_id VARCHAR(500) DEFAULT NULL');
        $this->addSql('ALTER TABLE agenda ADD google_sync_token TEXT DEFAULT NULL');
        $this->addSql('ALTER TABLE agenda ADD last_google_sync_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL');
        $this->addSql('ALTER TABLE agenda ADD google_watch_channel_id VARCHAR(64) DEFAULT NULL');
        $this->addSql('ALTER TABLE agenda ADD google_watch_resource_id VARCHAR(255) DEFAULT NULL');
        $this->addSql('ALTER TABLE agenda ADD google_watch_expires_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL');
        $this->addSql('ALTER TABLE event ADD google_event_id VARCHAR(1024) DEFAULT NULL');
        $this->addSql('ALTER TABLE event ADD google_etag VARCHAR(255) DEFAULT NULL');
        $this->addSql('ALTER TABLE event ADD google_updated_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL');
        $this->addSql('CREATE UNIQUE INDEX uniq_google_event_agenda ON event (google_event_id, agenda_id)');
    }

    public function down(Schema $schema): void
    {
        // this down() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE agenda DROP google_calendar_id');
        $this->addSql('ALTER TABLE agenda DROP google_sync_token');
        $this->addSql('ALTER TABLE agenda DROP last_google_sync_at');
        $this->addSql('ALTER TABLE agenda DROP google_watch_channel_id');
        $this->addSql('ALTER TABLE agenda DROP google_watch_resource_id');
        $this->addSql('ALTER TABLE agenda DROP google_watch_expires_at');
        $this->addSql('DROP INDEX uniq_google_event_agenda');
        $this->addSql('ALTER TABLE event DROP google_event_id');
        $this->addSql('ALTER TABLE event DROP google_etag');
        $this->addSql('ALTER TABLE event DROP google_updated_at');
        $this->addSql('ALTER TABLE "user" DROP google_access_token');
        $this->addSql('ALTER TABLE "user" DROP google_refresh_token');
        $this->addSql('ALTER TABLE "user" DROP google_token_expires_at');
    }
}
