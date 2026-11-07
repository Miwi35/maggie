<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The user's food journal (MAG-748): what was eaten, the day it counts for,
 * when, the planned meal or the Ciqual food it came from, and its figures
 * copied at the time — at most one intake per meal and user.
 */
final class Version20261107180000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add food_intake table';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE food_intake (id UUID NOT NULL, date DATE NOT NULL, eaten_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, meal_id VARCHAR(26) DEFAULT NULL, ciqual_code VARCHAR(16) DEFAULT NULL, label VARCHAR(255) NOT NULL, quantity_grams DOUBLE PRECISION DEFAULT NULL, kcal INT NOT NULL, protein_g DOUBLE PRECISION NOT NULL, carbs_g DOUBLE PRECISION NOT NULL, fat_g DOUBLE PRECISION NOT NULL, source VARCHAR(16) NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_EE8CEDD9A76ED395 ON food_intake (user_id)');
        $this->addSql('CREATE INDEX idx_food_intake_user_date ON food_intake (user_id, date)');
        $this->addSql('CREATE UNIQUE INDEX uniq_food_intake_user_meal ON food_intake (user_id, meal_id)');
        $this->addSql('ALTER TABLE food_intake ADD CONSTRAINT FK_EE8CEDD9A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE food_intake DROP CONSTRAINT FK_EE8CEDD9A76ED395');
        $this->addSql('DROP TABLE food_intake');
    }
}
