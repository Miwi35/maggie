<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20260707120158 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add transaction table for the finance module';
    }

    public function up(Schema $schema): void
    {
        // this up() migration is auto-generated, please modify it to your needs
        $this->addSql('CREATE TABLE transaction (id UUID NOT NULL, amount_cents INT NOT NULL, currency VARCHAR(3) NOT NULL, booked_at DATE NOT NULL, label VARCHAR(255) NOT NULL, status VARCHAR(20) NOT NULL, is_exceptional BOOLEAN NOT NULL, account_id UUID NOT NULL, category_id UUID DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_723705D19B6B5FBA ON transaction (account_id)');
        $this->addSql('CREATE INDEX IDX_723705D112469DE2 ON transaction (category_id)');
        $this->addSql('CREATE INDEX IDX_723705D1A76ED395 ON transaction (user_id)');
        $this->addSql('ALTER TABLE transaction ADD CONSTRAINT FK_723705D19B6B5FBA FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE transaction ADD CONSTRAINT FK_723705D112469DE2 FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('ALTER TABLE transaction ADD CONSTRAINT FK_723705D1A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        // this down() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE transaction DROP CONSTRAINT FK_723705D19B6B5FBA');
        $this->addSql('ALTER TABLE transaction DROP CONSTRAINT FK_723705D112469DE2');
        $this->addSql('ALTER TABLE transaction DROP CONSTRAINT FK_723705D1A76ED395');
        $this->addSql('DROP TABLE transaction');
    }
}
