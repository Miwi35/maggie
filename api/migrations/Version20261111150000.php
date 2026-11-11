<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Sessions kept, the streak and make-ups (MAG-771): what planned session a
 * session done keeps, the streak it reached, the make-up a planned session
 * is, and the make-up proposals of the missed ones.
 */
final class Version20261111150000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add workout.keeps_id, streak, makeup_of_id, makeup_option and workout_makeup';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE workout ADD keeps_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE workout ADD streak INT DEFAULT NULL');
        $this->addSql('ALTER TABLE workout ADD makeup_of_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE workout ADD makeup_option VARCHAR(16) DEFAULT NULL');
        $this->addSql('ALTER TABLE workout ADD CONSTRAINT FK_649FFB72704C6DB0 FOREIGN KEY (keeps_id) REFERENCES workout (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('ALTER TABLE workout ADD CONSTRAINT FK_649FFB72AAE7881 FOREIGN KEY (makeup_of_id) REFERENCES workout (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('CREATE UNIQUE INDEX uniq_workout_keeps ON workout (keeps_id)');
        $this->addSql('CREATE INDEX IDX_649FFB72AAE7881 ON workout (makeup_of_id)');
        $this->addSql('CREATE TABLE workout_makeup (id UUID NOT NULL, missed_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, softer_minutes INT NOT NULL, shorter_minutes INT NOT NULL, streak INT NOT NULL, chosen_option VARCHAR(16) DEFAULT NULL, status VARCHAR(16) NOT NULL, user_id UUID NOT NULL, missed_id UUID NOT NULL, chosen_workout_id UUID DEFAULT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_1B0F7952A76ED395 ON workout_makeup (user_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_workout_makeup_missed ON workout_makeup (missed_id)');
        $this->addSql('CREATE INDEX IDX_1B0F79525FC5A719 ON workout_makeup (chosen_workout_id)');
        $this->addSql('ALTER TABLE workout_makeup ADD CONSTRAINT FK_1B0F7952A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE workout_makeup ADD CONSTRAINT FK_1B0F7952A821F85F FOREIGN KEY (missed_id) REFERENCES workout (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE workout_makeup ADD CONSTRAINT FK_1B0F79525FC5A719 FOREIGN KEY (chosen_workout_id) REFERENCES workout (id) ON DELETE SET NULL NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('DROP TABLE workout_makeup');
        $this->addSql('ALTER TABLE workout DROP CONSTRAINT FK_649FFB72704C6DB0');
        $this->addSql('ALTER TABLE workout DROP CONSTRAINT FK_649FFB72AAE7881');
        $this->addSql('DROP INDEX uniq_workout_keeps');
        $this->addSql('DROP INDEX IDX_649FFB72AAE7881');
        $this->addSql('ALTER TABLE workout DROP keeps_id');
        $this->addSql('ALTER TABLE workout DROP streak');
        $this->addSql('ALTER TABLE workout DROP makeup_of_id');
        $this->addSql('ALTER TABLE workout DROP makeup_option');
    }
}
