<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * My part of a shared spend (MAG-451): the part the owner kept, which alone
 * counts in the envelopes, and the créance the rest becomes, one share per
 * person.
 */
final class Version20261017090000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add transaction.my_share_cents, create peer_debt and peer_debt_share';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction ADD my_share_cents INT DEFAULT NULL');
        $this->addSql('CREATE TABLE peer_debt (id UUID NOT NULL, direction VARCHAR(20) NOT NULL, object VARCHAR(255) NOT NULL, notes TEXT DEFAULT NULL, created_at TIMESTAMP(0) WITHOUT TIME ZONE NOT NULL, origin_transaction_id UUID DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_517D3BA9A76ED395 ON peer_debt (user_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_peer_debt_origin_transaction ON peer_debt (origin_transaction_id)');
        $this->addSql('CREATE TABLE peer_debt_share (id UUID NOT NULL, amount_cents INT NOT NULL, repaid_cents INT DEFAULT 0 NOT NULL, position INT DEFAULT 0 NOT NULL, peer_debt_id UUID NOT NULL, contact_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_79F784B1A2EE4137 ON peer_debt_share (peer_debt_id)');
        $this->addSql('CREATE INDEX IDX_79F784B1E7A1254A ON peer_debt_share (contact_id)');
        $this->addSql('ALTER TABLE peer_debt ADD CONSTRAINT FK_517D3BA9F7E4F775 FOREIGN KEY (origin_transaction_id) REFERENCES transaction (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('ALTER TABLE peer_debt ADD CONSTRAINT FK_517D3BA9A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE peer_debt_share ADD CONSTRAINT FK_79F784B1A2EE4137 FOREIGN KEY (peer_debt_id) REFERENCES peer_debt (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE peer_debt_share ADD CONSTRAINT FK_79F784B1E7A1254A FOREIGN KEY (contact_id) REFERENCES contact (id) NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE peer_debt_share DROP CONSTRAINT FK_79F784B1A2EE4137');
        $this->addSql('ALTER TABLE peer_debt_share DROP CONSTRAINT FK_79F784B1E7A1254A');
        $this->addSql('ALTER TABLE peer_debt DROP CONSTRAINT FK_517D3BA9F7E4F775');
        $this->addSql('ALTER TABLE peer_debt DROP CONSTRAINT FK_517D3BA9A76ED395');
        $this->addSql('DROP TABLE peer_debt_share');
        $this->addSql('DROP TABLE peer_debt');
        $this->addSql('ALTER TABLE transaction DROP my_share_cents');
    }
}
