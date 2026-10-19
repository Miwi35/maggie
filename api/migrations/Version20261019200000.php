<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Reminders that change their words (MAG-510): one row per task reminder
 * planned, sent or dropped; the daily cap, the quiet hours and the moments of
 * the day in the task settings, at their defaults for every user already
 * there; the buttons a notification offers and the reminder they answer.
 */
final class Version20261019200000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create task_reminder, add day_moments, reminder_daily_cap, quiet_start and quiet_end to task_settings, reminder_id and actions to notification';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE task_reminder (id UUID NOT NULL, kind VARCHAR(20) NOT NULL, subject_id VARCHAR(36) NOT NULL, moment VARCHAR(20) DEFAULT NULL, scheduled_for TIMESTAMP(0) WITH TIME ZONE NOT NULL, sent_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, dropped_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, snoozed BOOLEAN DEFAULT false NOT NULL, message_key VARCHAR(40) DEFAULT NULL, action VARCHAR(20) DEFAULT NULL, user_id UUID NOT NULL, notification_id UUID DEFAULT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_F7A50CA76ED395 ON task_reminder (user_id)');
        $this->addSql('CREATE INDEX IDX_F7A50CEF1A9D84 ON task_reminder (notification_id)');
        $this->addSql('CREATE INDEX idx_task_reminder_user_scheduled ON task_reminder (user_id, scheduled_for)');
        $this->addSql('ALTER TABLE task_reminder ADD CONSTRAINT FK_F7A50CA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE task_reminder ADD CONSTRAINT FK_F7A50CEF1A9D84 FOREIGN KEY (notification_id) REFERENCES notification (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('ALTER TABLE task_settings ADD day_moments JSON DEFAULT \'{"wake":"07:30","afterBreakfast":"08:30","afterLunch":"13:30","afterDinner":"20:00"}\' NOT NULL');
        $this->addSql('ALTER TABLE task_settings ADD reminder_daily_cap INT DEFAULT 6 NOT NULL');
        $this->addSql('ALTER TABLE task_settings ADD quiet_start VARCHAR(5) DEFAULT \'21:00\' NOT NULL');
        $this->addSql('ALTER TABLE task_settings ADD quiet_end VARCHAR(5) DEFAULT \'08:00\' NOT NULL');
        $this->addSql('ALTER TABLE notification ADD reminder_id VARCHAR(26) DEFAULT NULL');
        $this->addSql('ALTER TABLE notification ADD actions JSON DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE notification DROP actions');
        $this->addSql('ALTER TABLE notification DROP reminder_id');
        $this->addSql('ALTER TABLE task_settings DROP quiet_end');
        $this->addSql('ALTER TABLE task_settings DROP quiet_start');
        $this->addSql('ALTER TABLE task_settings DROP reminder_daily_cap');
        $this->addSql('ALTER TABLE task_settings DROP day_moments');
        $this->addSql('ALTER TABLE task_reminder DROP CONSTRAINT FK_F7A50CEF1A9D84');
        $this->addSql('ALTER TABLE task_reminder DROP CONSTRAINT FK_F7A50CA76ED395');
        $this->addSql('DROP TABLE task_reminder');
    }
}
