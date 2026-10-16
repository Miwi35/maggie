<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Contacts and their counterparties (MAG-450): the people the owner lends to
 * or borrows from, the labels under which they appear on the statements, one
 * label per user, and the contact each transaction is attached to.
 */
final class Version20261016140000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create contact and contact_counterparty, add transaction.contact_id';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE contact (id UUID NOT NULL, name VARCHAR(255) NOT NULL, notes TEXT DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_4C62E638A76ED395 ON contact (user_id)');
        $this->addSql('CREATE TABLE contact_counterparty (id UUID NOT NULL, label VARCHAR(255) NOT NULL, match_key VARCHAR(255) NOT NULL, contact_id UUID NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_B78AD5A8E7A1254A ON contact_counterparty (contact_id)');
        $this->addSql('CREATE INDEX IDX_B78AD5A8A76ED395 ON contact_counterparty (user_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_contact_counterparty_user_key ON contact_counterparty (user_id, match_key)');
        $this->addSql('ALTER TABLE contact ADD CONSTRAINT FK_4C62E638A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE contact_counterparty ADD CONSTRAINT FK_B78AD5A8E7A1254A FOREIGN KEY (contact_id) REFERENCES contact (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE contact_counterparty ADD CONSTRAINT FK_B78AD5A8A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE transaction ADD contact_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE transaction ADD CONSTRAINT FK_723705D1E7A1254A FOREIGN KEY (contact_id) REFERENCES contact (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_723705D1E7A1254A ON transaction (contact_id)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction DROP CONSTRAINT FK_723705D1E7A1254A');
        $this->addSql('DROP INDEX IDX_723705D1E7A1254A');
        $this->addSql('ALTER TABLE transaction DROP contact_id');
        $this->addSql('ALTER TABLE contact_counterparty DROP CONSTRAINT FK_B78AD5A8E7A1254A');
        $this->addSql('ALTER TABLE contact_counterparty DROP CONSTRAINT FK_B78AD5A8A76ED395');
        $this->addSql('ALTER TABLE contact DROP CONSTRAINT FK_4C62E638A76ED395');
        $this->addSql('DROP TABLE contact_counterparty');
        $this->addSql('DROP TABLE contact');
    }
}
