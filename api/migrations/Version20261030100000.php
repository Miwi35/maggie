<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The user's health profile (MAG-707): sex, year of birth, height and activity
 * level for the day's need, or the need typed, and the physical activity
 * settings. One row per user.
 */
final class Version20261030100000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add health_profile table';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE health_profile (id UUID NOT NULL, sex VARCHAR(8) DEFAULT NULL, birth_year SMALLINT DEFAULT NULL, height_cm SMALLINT DEFAULT NULL, activity_level VARCHAR(16) DEFAULT \'sedentary\' NOT NULL, mode VARCHAR(16) DEFAULT \'computed\' NOT NULL, entered_need_kcal SMALLINT DEFAULT NULL, step_goal INT DEFAULT 8000 NOT NULL, suggestion_window_start VARCHAR(5) DEFAULT \'08:00\' NOT NULL, suggestion_window_end VARCHAR(5) DEFAULT \'20:00\' NOT NULL, session_minutes SMALLINT DEFAULT 20 NOT NULL, step_source VARCHAR(16) DEFAULT \'none\' NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX uniq_health_profile_user ON health_profile (user_id)');
        $this->addSql('ALTER TABLE health_profile ADD CONSTRAINT FK_1CFE3748A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE health_profile DROP CONSTRAINT FK_1CFE3748A76ED395');
        $this->addSql('DROP TABLE health_profile');
    }
}
