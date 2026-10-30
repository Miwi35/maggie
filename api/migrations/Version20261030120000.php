<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * A day's steps and active energy, as Health Connect aggregates them on the
 * phone (MAG-708). One row per user and local date.
 */
final class Version20261030120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add daily_activity table';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE daily_activity (id UUID NOT NULL, date DATE NOT NULL, steps INT NOT NULL, active_kcal INT NOT NULL, source VARCHAR(16) NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_167BDE8EA76ED395 ON daily_activity (user_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_daily_activity_user_date ON daily_activity (user_id, date)');
        $this->addSql('ALTER TABLE daily_activity ADD CONSTRAINT FK_167BDE8EA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE daily_activity DROP CONSTRAINT FK_167BDE8EA76ED395');
        $this->addSql('DROP TABLE daily_activity');
    }
}
