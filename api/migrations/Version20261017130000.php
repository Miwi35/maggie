<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The bravo of a task done (MAG-508): one reward per user and subject, and
 * how it is cheered — animation, vibration, sound on, the « doux » tone — for
 * every user already there.
 */
final class Version20261017130000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create reward, add reward_animation, reward_vibration, reward_sound and tone to task_settings';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE reward (id UUID NOT NULL, subject_type VARCHAR(20) NOT NULL, subject_id VARCHAR(36) NOT NULL, level VARCHAR(20) NOT NULL, surprise VARCHAR(20) DEFAULT NULL, message_key VARCHAR(32) NOT NULL, message TEXT NOT NULL, star BOOLEAN DEFAULT true NOT NULL, earned_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_4ED17253A76ED395 ON reward (user_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_reward_subject ON reward (user_id, subject_type, subject_id)');
        $this->addSql('ALTER TABLE reward ADD CONSTRAINT FK_4ED17253A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE task_settings ADD reward_animation BOOLEAN DEFAULT true NOT NULL');
        $this->addSql('ALTER TABLE task_settings ADD reward_vibration BOOLEAN DEFAULT true NOT NULL');
        $this->addSql('ALTER TABLE task_settings ADD reward_sound BOOLEAN DEFAULT true NOT NULL');
        $this->addSql('ALTER TABLE task_settings ADD tone VARCHAR(10) DEFAULT \'doux\' NOT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE task_settings DROP tone');
        $this->addSql('ALTER TABLE task_settings DROP reward_sound');
        $this->addSql('ALTER TABLE task_settings DROP reward_vibration');
        $this->addSql('ALTER TABLE task_settings DROP reward_animation');
        $this->addSql('ALTER TABLE reward DROP CONSTRAINT FK_4ED17253A76ED395');
        $this->addSql('DROP TABLE reward');
    }
}
