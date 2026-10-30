<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The user's training sessions (MAG-705): type, title, start, duration in
 * minutes, optional distance in metres, kcal, heart rate and feeling, where it
 * comes from, and an external id unique per user to de-duplicate imports.
 */
final class Version20261029010000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add workout table';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE workout (id UUID NOT NULL, type VARCHAR(16) NOT NULL, title VARCHAR(255) NOT NULL, started_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, duration_minutes INT NOT NULL, distance_meters INT DEFAULT NULL, kcal INT DEFAULT NULL, avg_heart_rate INT DEFAULT NULL, feeling VARCHAR(16) DEFAULT NULL, notes TEXT DEFAULT NULL, source VARCHAR(16) NOT NULL, external_id VARCHAR(255) DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_649FFB72A76ED395 ON workout (user_id)');
        $this->addSql('CREATE INDEX idx_workout_user_started_at ON workout (user_id, started_at)');
        $this->addSql('CREATE UNIQUE INDEX uniq_workout_user_external ON workout (user_id, external_id)');
        $this->addSql('ALTER TABLE workout ADD CONSTRAINT FK_649FFB72A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE workout DROP CONSTRAINT FK_649FFB72A76ED395');
        $this->addSql('DROP TABLE workout');
    }
}
