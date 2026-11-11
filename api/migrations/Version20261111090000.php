<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The detailed session (MAG-775): a workout's exercises, prescribed and done
 * set by set, how each felt, and the session's difficulty out of 10 and coach
 * variant.
 */
final class Version20261111090000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add workout_exercise, workout_set, workout.difficulty and workout.variant';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE workout ADD difficulty SMALLINT DEFAULT NULL');
        $this->addSql('ALTER TABLE workout ADD variant VARCHAR(16) DEFAULT NULL');
        $this->addSql('CREATE TABLE workout_exercise (id UUID NOT NULL, position INT NOT NULL, exercise VARCHAR(64) NOT NULL, step INT NOT NULL, sets INT NOT NULL, target INT NOT NULL, target_min INT NOT NULL, target_max INT NOT NULL, load_kg DOUBLE PRECISION DEFAULT NULL, rest_seconds INT NOT NULL, tempo BOOLEAN DEFAULT false NOT NULL, target_rir INT DEFAULT NULL, cue VARCHAR(255) DEFAULT NULL, feeling VARCHAR(16) DEFAULT NULL, workout_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX idx_workout_exercise_position ON workout_exercise (workout_id, position)');
        $this->addSql('CREATE INDEX idx_workout_exercise_exercise ON workout_exercise (exercise)');
        $this->addSql('ALTER TABLE workout_exercise ADD CONSTRAINT FK_76AB38AAA6CCCFC9 FOREIGN KEY (workout_id) REFERENCES workout (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('CREATE TABLE workout_set (id UUID NOT NULL, position INT NOT NULL, reps INT DEFAULT NULL, seconds INT DEFAULT NULL, meters INT DEFAULT NULL, load_kg DOUBLE PRECISION DEFAULT NULL, workout_exercise_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX idx_workout_set_position ON workout_set (workout_exercise_id, position)');
        $this->addSql('ALTER TABLE workout_set ADD CONSTRAINT FK_6FDEFB94E435DB6B FOREIGN KEY (workout_exercise_id) REFERENCES workout_exercise (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE workout_set DROP CONSTRAINT FK_6FDEFB94E435DB6B');
        $this->addSql('ALTER TABLE workout_exercise DROP CONSTRAINT FK_76AB38AAA6CCCFC9');
        $this->addSql('DROP TABLE workout_set');
        $this->addSql('DROP TABLE workout_exercise');
        $this->addSql('ALTER TABLE workout DROP difficulty');
        $this->addSql('ALTER TABLE workout DROP variant');
    }
}
