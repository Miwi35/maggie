<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The health journal (MAG-750): one row per user and local day, the day's
 * figures read from the detailed tables, and the mood, fatigue and note
 * typed by the user or Maggie. Filled for the past by app:health:rebuild-days.
 */
final class Version20261107210000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add health_day table';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE health_day (id UUID NOT NULL, date DATE NOT NULL, weight_kg NUMERIC(5, 2) DEFAULT NULL, body_fat_percent NUMERIC(4, 1) DEFAULT NULL, weighed_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, steps INT DEFAULT NULL, eaten_kcal INT DEFAULT NULL, expended_kcal INT DEFAULT NULL, sport_minutes INT DEFAULT NULL, mood SMALLINT DEFAULT NULL, fatigue SMALLINT DEFAULT NULL, note TEXT DEFAULT NULL, noted_by VARCHAR(16) DEFAULT NULL, noted_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_D767F280A76ED395 ON health_day (user_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_health_day_user_date ON health_day (user_id, date)');
        $this->addSql('ALTER TABLE health_day ADD CONSTRAINT FK_D767F280A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE health_day DROP CONSTRAINT FK_D767F280A76ED395');
        $this->addSql('DROP TABLE health_day');
    }
}
