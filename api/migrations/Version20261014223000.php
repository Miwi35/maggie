<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The task settings, one row per user, created on first read (MAG-406). They
 * hold the weights of the order score; no row means the defaults.
 */
final class Version20261014223000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create task_settings, with the order score weights';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE task_settings (id UUID NOT NULL, order_weights JSON NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX uniq_task_settings_user ON task_settings (user_id)');
        $this->addSql('ALTER TABLE task_settings ADD CONSTRAINT FK_A5854A89A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE task_settings DROP CONSTRAINT FK_A5854A89A76ED395');
        $this->addSql('DROP TABLE task_settings');
    }
}
