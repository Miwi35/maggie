<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20260212173447 extends AbstractMigration
{
    public function getDescription(): string
    {
        return '';
    }

    public function up(Schema $schema): void
    {
        // this up() migration is auto-generated, please modify it to your needs
        $this->addSql('CREATE TABLE task (id UUID NOT NULL, name VARCHAR(255) NOT NULL, description TEXT DEFAULT NULL, priority VARCHAR(20) DEFAULT \'medium\' NOT NULL, criticality VARCHAR(20) DEFAULT \'low\' NOT NULL, due_date TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, done_date TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX idx_task_due_date ON task (due_date)');
        $this->addSql('CREATE INDEX idx_task_done_date ON task (done_date)');
    }

    public function down(Schema $schema): void
    {
        // this down() migration is auto-generated, please modify it to your needs
        $this->addSql('DROP TABLE task');
    }
}
