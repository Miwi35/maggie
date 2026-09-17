<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20260917124010 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add loan table (fixed charges being repaid)';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE loan (id UUID NOT NULL, name VARCHAR(255) NOT NULL, lender VARCHAR(255) DEFAULT NULL, principal_remaining_cents INT NOT NULL, monthly_payment_cents INT NOT NULL, annual_rate_basis_points INT NOT NULL, priority INT NOT NULL, currency VARCHAR(3) NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_C5D30D03A76ED395 ON loan (user_id)');
        $this->addSql('ALTER TABLE loan ADD CONSTRAINT FK_C5D30D03A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE loan DROP CONSTRAINT FK_C5D30D03A76ED395');
        $this->addSql('DROP TABLE loan');
    }
}
