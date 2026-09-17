<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20260917075659 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add safety_cushion table (emergency fund configuration)';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE safety_cushion (id UUID NOT NULL, target_months INT DEFAULT 3 NOT NULL, monthly_net_income_cents INT DEFAULT 0 NOT NULL, recharge_cap_cents INT DEFAULT 15000 NOT NULL, recharge_target_months INT DEFAULT 6 NOT NULL, completed_at TIMESTAMP(0) WITHOUT TIME ZONE DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX uniq_safety_cushion_user ON safety_cushion (user_id)');
        $this->addSql('ALTER TABLE safety_cushion ADD CONSTRAINT FK_F3A0EA77A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE safety_cushion DROP CONSTRAINT FK_F3A0EA77A76ED395');
        $this->addSql('DROP TABLE safety_cushion');
    }
}
