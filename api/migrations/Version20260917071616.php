<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20260917071616 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add categorization_rule table and category provenance on transaction';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE categorization_rule (id UUID NOT NULL, label_pattern VARCHAR(255) NOT NULL, match_type VARCHAR(20) NOT NULL, direction VARCHAR(20) NOT NULL, min_amount_cents INT DEFAULT NULL, max_amount_cents INT DEFAULT NULL, priority INT NOT NULL, is_active BOOLEAN NOT NULL, category_id UUID NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_F056C98A12469DE2 ON categorization_rule (category_id)');
        $this->addSql('CREATE INDEX IDX_F056C98AA76ED395 ON categorization_rule (user_id)');
        $this->addSql('ALTER TABLE categorization_rule ADD CONSTRAINT FK_F056C98A12469DE2 FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE categorization_rule ADD CONSTRAINT FK_F056C98AA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        // Existing rows need a value before the column can be NOT NULL: anything
        // already categorized was categorized by hand.
        $this->addSql("ALTER TABLE transaction ADD category_source VARCHAR(20) DEFAULT 'none' NOT NULL");
        $this->addSql('ALTER TABLE transaction ADD categorized_at TIMESTAMP(0) WITHOUT TIME ZONE DEFAULT NULL');
        $this->addSql("UPDATE transaction SET category_source = 'manual' WHERE category_id IS NOT NULL");
        $this->addSql('ALTER TABLE transaction ALTER COLUMN category_source DROP DEFAULT');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE categorization_rule DROP CONSTRAINT FK_F056C98A12469DE2');
        $this->addSql('ALTER TABLE categorization_rule DROP CONSTRAINT FK_F056C98AA76ED395');
        $this->addSql('DROP TABLE categorization_rule');
        $this->addSql('ALTER TABLE transaction DROP category_source');
        $this->addSql('ALTER TABLE transaction DROP categorized_at');
    }
}
