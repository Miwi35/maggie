<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20260211185015 extends AbstractMigration
{
    public function getDescription(): string
    {
        return '';
    }

    public function up(Schema $schema): void
    {
        // this up() migration is auto-generated, please modify it to your needs
        $this->addSql('DROP SEQUENCE IF EXISTS greeting_id_seq CASCADE');
        $this->addSql('DROP SEQUENCE IF EXISTS user_id_seq CASCADE');
        $this->addSql('CREATE TABLE calendar (id UUID NOT NULL, name VARCHAR(255) NOT NULL, description TEXT DEFAULT NULL, time_zone VARCHAR(50) DEFAULT \'Europe/Paris\' NOT NULL, color VARCHAR(7) DEFAULT NULL, is_default BOOLEAN DEFAULT false NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE TABLE event (id UUID NOT NULL, summary VARCHAR(255) NOT NULL, description TEXT DEFAULT NULL, location VARCHAR(500) DEFAULT NULL, all_day BOOLEAN DEFAULT false NOT NULL, start_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, end_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, time_zone VARCHAR(50) DEFAULT \'Europe/Paris\' NOT NULL, rrule VARCHAR(500) DEFAULT NULL, original_start_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, status VARCHAR(20) DEFAULT \'confirmed\' NOT NULL, reminders JSON DEFAULT NULL, recurring_event_id UUID DEFAULT NULL, calendar_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_3BAE0AA7E54B259A ON event (recurring_event_id)');
        $this->addSql('CREATE INDEX IDX_3BAE0AA7A40A2C8 ON event (calendar_id)');
        $this->addSql('CREATE INDEX idx_event_dates ON event (start_at, end_at)');
        $this->addSql('CREATE INDEX idx_event_status ON event (status)');
        $this->addSql('ALTER TABLE event ADD CONSTRAINT FK_3BAE0AA7E54B259A FOREIGN KEY (recurring_event_id) REFERENCES event (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE event ADD CONSTRAINT FK_3BAE0AA7A40A2C8 FOREIGN KEY (calendar_id) REFERENCES calendar (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('DROP TABLE IF EXISTS greeting');
        $this->addSql('DROP TABLE IF EXISTS "user"');
    }

    public function down(Schema $schema): void
    {
        // this down() migration is auto-generated, please modify it to your needs
        $this->addSql('CREATE SEQUENCE greeting_id_seq INCREMENT BY 1 MINVALUE 1 START 1');
        $this->addSql('CREATE SEQUENCE user_id_seq INCREMENT BY 1 MINVALUE 1 START 1');
        $this->addSql('CREATE TABLE greeting (id INT NOT NULL, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE TABLE "user" (id INT NOT NULL, email VARCHAR(180) NOT NULL, roles JSON NOT NULL, password VARCHAR(255) NOT NULL, username VARCHAR(180) NOT NULL, is_verified BOOLEAN NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX uniq_8d93d649f85e0677 ON "user" (username)');
        $this->addSql('CREATE UNIQUE INDEX uniq_8d93d649e7927c74 ON "user" (email)');
        $this->addSql('ALTER TABLE event DROP CONSTRAINT FK_3BAE0AA7E54B259A');
        $this->addSql('ALTER TABLE event DROP CONSTRAINT FK_3BAE0AA7A40A2C8');
        $this->addSql('DROP TABLE calendar');
        $this->addSql('DROP TABLE event');
    }
}
