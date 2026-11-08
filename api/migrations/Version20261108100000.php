<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** A grocery article found missing in a store, and the store it was missing from (MAG-764). */
final class Version20261108100000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add missing_at and missing_from_store_id to grocery_item';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE grocery_item ADD missing_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL');
        $this->addSql('ALTER TABLE grocery_item ADD missing_from_store_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE grocery_item ADD CONSTRAINT FK_8F9EDB8AFF0BC3C9 FOREIGN KEY (missing_from_store_id) REFERENCES store (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_8F9EDB8AFF0BC3C9 ON grocery_item (missing_from_store_id)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE grocery_item DROP CONSTRAINT FK_8F9EDB8AFF0BC3C9');
        $this->addSql('DROP INDEX IDX_8F9EDB8AFF0BC3C9');
        $this->addSql('ALTER TABLE grocery_item DROP missing_from_store_id');
        $this->addSql('ALTER TABLE grocery_item DROP missing_at');
    }
}
