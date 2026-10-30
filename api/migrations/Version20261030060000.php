<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The user's body measurements (MAG-706): when, an optional weight, body fat
 * and waist size, where it comes from, an external id unique per user to
 * de-duplicate imports, and whether it is set aside.
 */
final class Version20261030060000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add body_measurement table';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE body_measurement (id UUID NOT NULL, measured_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, weight_kg NUMERIC(5, 2) DEFAULT NULL, body_fat_percent NUMERIC(4, 1) DEFAULT NULL, waist_cm NUMERIC(5, 1) DEFAULT NULL, source VARCHAR(16) NOT NULL, external_id VARCHAR(255) DEFAULT NULL, excluded BOOLEAN DEFAULT false NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_E574BF11A76ED395 ON body_measurement (user_id)');
        $this->addSql('CREATE INDEX idx_body_measurement_user_measured_at ON body_measurement (user_id, measured_at)');
        $this->addSql('CREATE UNIQUE INDEX uniq_body_measurement_user_external ON body_measurement (user_id, external_id)');
        $this->addSql('ALTER TABLE body_measurement ADD CONSTRAINT FK_E574BF11A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE body_measurement DROP CONSTRAINT FK_E574BF11A76ED395');
        $this->addSql('DROP TABLE body_measurement');
    }
}
