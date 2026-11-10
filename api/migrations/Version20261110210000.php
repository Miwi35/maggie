<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Intermittent fasting (MAG-770): the protocol and the start of the eating
 * window on the health profile, and the night's fast on the health journal.
 * The journal's column is filled for the past by `app:health:rebuild-days`.
 */
final class Version20261110210000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add fasting protocol and window start to health_profile, night_fast_minutes to health_day';
    }

    public function up(Schema $schema): void
    {
        $this->addSql("ALTER TABLE health_profile ADD fasting_protocol VARCHAR(5) DEFAULT '16:8'");
        $this->addSql("ALTER TABLE health_profile ADD fasting_window_start VARCHAR(5) DEFAULT '12:00' NOT NULL");
        $this->addSql('ALTER TABLE health_day ADD night_fast_minutes INT DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE health_profile DROP fasting_protocol');
        $this->addSql('ALTER TABLE health_profile DROP fasting_window_start');
        $this->addSql('ALTER TABLE health_day DROP night_fast_minutes');
    }
}
