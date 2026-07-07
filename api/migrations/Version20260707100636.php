<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20260707100636 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add account table for the finance module';
    }

    public function up(Schema $schema): void
    {
        // this up() migration is auto-generated, please modify it to your needs
        $this->addSql('CREATE TABLE account (id UUID NOT NULL, name VARCHAR(255) NOT NULL, bank VARCHAR(120) DEFAULT NULL, type VARCHAR(20) NOT NULL, currency VARCHAR(3) NOT NULL, balance_cents INT NOT NULL, is_cushion BOOLEAN NOT NULL, bridge_account_id VARCHAR(255) DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_7D3656A4A76ED395 ON account (user_id)');
        $this->addSql('ALTER TABLE account ADD CONSTRAINT FK_7D3656A4A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        // this down() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE account DROP CONSTRAINT FK_7D3656A4A76ED395');
        $this->addSql('DROP TABLE account');
    }
}
