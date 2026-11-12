<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** The periods (MAG-809): a name, an agenda, an event's schedule and the public holidays they stand for. */
final class Version20261112120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create the period table';
    }

    public function up(Schema $schema): void
    {
        $this->addSql("CREATE TABLE period (id UUID NOT NULL, name VARCHAR(255) NOT NULL, all_day BOOLEAN DEFAULT false NOT NULL, start_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, end_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, start_date DATE DEFAULT NULL, end_date DATE DEFAULT NULL, time_zone VARCHAR(50) DEFAULT 'Europe/Paris' NOT NULL, rrule VARCHAR(500) DEFAULT NULL, holiday VARCHAR(10) DEFAULT NULL, agenda_id UUID NOT NULL, PRIMARY KEY (id))");
        $this->addSql('CREATE INDEX IDX_C5B81ECEEA67784A ON period (agenda_id)');
        $this->addSql('CREATE INDEX idx_period_dates ON period (start_at, end_at)');
        $this->addSql('CREATE INDEX idx_period_days ON period (start_date, end_date)');
        $this->addSql('ALTER TABLE period ADD CONSTRAINT FK_C5B81ECEEA67784A FOREIGN KEY (agenda_id) REFERENCES agenda (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE period DROP CONSTRAINT FK_C5B81ECEEA67784A');
        $this->addSql('DROP TABLE period');
    }
}
