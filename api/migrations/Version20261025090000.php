<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The timers of the Cuisine activity (MAG-582): their end lives in the API, so
 * every device of the user hears about it and a reload reads it back.
 */
final class Version20261025090000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add cooking_timer';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE cooking_timer (id UUID NOT NULL, label VARCHAR(80) NOT NULL, step_position INT NOT NULL, duration_seconds INT NOT NULL, ends_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, paused_remaining_seconds INT DEFAULT NULL, status VARCHAR(16) NOT NULL, revision INT DEFAULT 0 NOT NULL, finished_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, created_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, user_id UUID NOT NULL, recipe_id UUID DEFAULT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_263AFA2AA76ED395 ON cooking_timer (user_id)');
        $this->addSql('CREATE INDEX IDX_263AFA2A59D8A214 ON cooking_timer (recipe_id)');
        $this->addSql('CREATE INDEX idx_cooking_timer_user_status ON cooking_timer (user_id, status)');
        $this->addSql('ALTER TABLE cooking_timer ADD CONSTRAINT FK_263AFA2AA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE cooking_timer ADD CONSTRAINT FK_263AFA2A59D8A214 FOREIGN KEY (recipe_id) REFERENCES recipe (id) ON DELETE SET NULL NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE cooking_timer DROP CONSTRAINT FK_263AFA2AA76ED395');
        $this->addSql('ALTER TABLE cooking_timer DROP CONSTRAINT FK_263AFA2A59D8A214');
        $this->addSql('DROP TABLE cooking_timer');
    }
}
