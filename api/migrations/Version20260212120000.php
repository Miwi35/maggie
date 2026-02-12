<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Rename calendar table to agenda and calendar_id column to agenda_id.
 */
final class Version20260212120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Rename calendar table to agenda and calendar_id to agenda_id';
    }

    public function up(Schema $schema): void
    {
        // Drop FK constraints referencing the old table/column
        $this->addSql('ALTER TABLE event DROP CONSTRAINT FK_3BAE0AA7A40A2C8');

        // Rename table
        $this->addSql('ALTER TABLE calendar RENAME TO agenda');

        // Rename FK column
        $this->addSql('ALTER TABLE event RENAME COLUMN calendar_id TO agenda_id');

        // Recreate FK constraint with new names
        $this->addSql('ALTER TABLE event ADD CONSTRAINT FK_3BAE0AA7EA67784A FOREIGN KEY (agenda_id) REFERENCES agenda (id) ON DELETE CASCADE NOT DEFERRABLE');

        // Drop old index and create new one
        $this->addSql('DROP INDEX IF EXISTS IDX_3BAE0AA7A40A2C8');
        $this->addSql('CREATE INDEX IDX_3BAE0AA7EA67784A ON event (agenda_id)');
    }

    public function down(Schema $schema): void
    {
        // Drop new FK constraint
        $this->addSql('ALTER TABLE event DROP CONSTRAINT FK_3BAE0AA7EA67784A');

        // Rename back
        $this->addSql('ALTER TABLE agenda RENAME TO calendar');
        $this->addSql('ALTER TABLE event RENAME COLUMN agenda_id TO calendar_id');

        // Recreate original FK constraint
        $this->addSql('ALTER TABLE event ADD CONSTRAINT FK_3BAE0AA7A40A2C8 FOREIGN KEY (calendar_id) REFERENCES calendar (id) ON DELETE CASCADE NOT DEFERRABLE');

        // Drop new index and recreate old one
        $this->addSql('DROP INDEX IF EXISTS IDX_3BAE0AA7EA67784A');
        $this->addSql('CREATE INDEX IDX_3BAE0AA7A40A2C8 ON event (calendar_id)');
    }
}
