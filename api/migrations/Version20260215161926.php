<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20260215161926 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create memory table with full-text search GIN index';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE memory (id UUID NOT NULL, type VARCHAR(20) NOT NULL, content TEXT NOT NULL, metadata JSON DEFAULT NULL, created_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, updated_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_EA6D3435A76ED395 ON memory (user_id)');
        $this->addSql('CREATE INDEX idx_memory_type ON memory (type)');
        $this->addSql('CREATE INDEX idx_memory_user_type ON memory (user_id, type)');
        $this->addSql('CREATE INDEX idx_memory_created_at ON memory (created_at)');
        $this->addSql('ALTER TABLE memory ADD CONSTRAINT FK_EA6D3435A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql("CREATE INDEX idx_memory_content_fts ON memory USING GIN (to_tsvector('french', content))");
    }

    public function down(Schema $schema): void
    {
        $this->addSql('DROP INDEX idx_memory_content_fts');
        $this->addSql('ALTER TABLE memory DROP CONSTRAINT FK_EA6D3435A76ED395');
        $this->addSql('DROP TABLE memory');
    }
}
