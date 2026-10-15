<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * One-off envelopes and the hand attribution of a spend to one (MAG-414).
 *
 * A one-off envelope (`mode` = `one_off`, « Hellfest 2026 ») has a name but
 * neither category nor period, so `envelope.category_id` and `envelope.year`
 * become nullable; the entity keeps them required for monthly and annual
 * envelopes. `transaction.one_off_envelope_id` attributes a spend to one:
 * the spend keeps its category but leaves the monthly and annual envelopes.
 * Deleting the envelope frees its spends.
 */
final class Version20261014230000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add one-off envelopes (envelope.name, nullable category and year) and transaction.one_off_envelope_id';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE envelope ADD name VARCHAR(120) DEFAULT NULL');
        $this->addSql('ALTER TABLE envelope ALTER category_id DROP NOT NULL');
        $this->addSql('ALTER TABLE envelope ALTER year DROP NOT NULL');
        $this->addSql('ALTER TABLE transaction ADD one_off_envelope_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE transaction ADD CONSTRAINT FK_723705D163D4F37C FOREIGN KEY (one_off_envelope_id) REFERENCES envelope (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_723705D163D4F37C ON transaction (one_off_envelope_id)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction DROP CONSTRAINT FK_723705D163D4F37C');
        $this->addSql('DROP INDEX IDX_723705D163D4F37C');
        $this->addSql('ALTER TABLE transaction DROP one_off_envelope_id');
        $this->addSql("DELETE FROM envelope WHERE mode = 'one_off'");
        $this->addSql('ALTER TABLE envelope ALTER year SET NOT NULL');
        $this->addSql('ALTER TABLE envelope ALTER category_id SET NOT NULL');
        $this->addSql('ALTER TABLE envelope DROP name');
    }
}
