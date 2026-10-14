<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Maggie interrupts when a bank sync takes an envelope past its threshold
 * (MAG-49): the threshold lives on the envelope (null = 80 %), and each alert
 * sent is kept so a threshold alerts once per period.
 */
final class Version20261015090000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add envelope.alert_threshold_percent and the envelope_alert table';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE envelope ADD alert_threshold_percent INT DEFAULT NULL');
        $this->addSql('CREATE TABLE envelope_alert (id UUID NOT NULL, sent_at TIMESTAMP(0) WITHOUT TIME ZONE NOT NULL, envelope_id UUID NOT NULL, period VARCHAR(7) NOT NULL, threshold_percent INT NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX uniq_envelope_alert ON envelope_alert (envelope_id, period, threshold_percent)');
        $this->addSql('ALTER TABLE envelope_alert ADD CONSTRAINT FK_D6FC03A44706CB17 FOREIGN KEY (envelope_id) REFERENCES envelope (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('DROP TABLE envelope_alert');
        $this->addSql('ALTER TABLE envelope DROP alert_threshold_percent');
    }
}
