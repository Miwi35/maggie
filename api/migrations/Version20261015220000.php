<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The day (MAG-497): one row per user and local date, its five slots at most
 * held as JSON. And the two fields of a task that are Maggie's own — how long
 * it takes, its first step — null for every task already there.
 */
final class Version20261015220000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create day_plan, add estimated_minutes and first_step to task';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE day_plan (id UUID NOT NULL, date DATE NOT NULL, slots JSON DEFAULT \'[]\' NOT NULL, confirmed_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_7632B60BA76ED395 ON day_plan (user_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_day_plan_user_date ON day_plan (user_id, date)');
        $this->addSql('ALTER TABLE day_plan ADD CONSTRAINT FK_7632B60BA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE task ADD estimated_minutes INT DEFAULT NULL');
        $this->addSql('ALTER TABLE task ADD first_step VARCHAR(255) DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE task DROP first_step');
        $this->addSql('ALTER TABLE task DROP estimated_minutes');
        $this->addSql('ALTER TABLE day_plan DROP CONSTRAINT FK_7632B60BA76ED395');
        $this->addSql('DROP TABLE day_plan');
    }
}
