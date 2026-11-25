<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The occurrence a done recurring task handed its rule to (MAG-1033):
 * reopening the done one gives the series back.
 */
final class Version20261125120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add task.next_occurrence_id';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE task ADD next_occurrence_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE task ADD CONSTRAINT FK_527EDB25705F2BCA FOREIGN KEY (next_occurrence_id) REFERENCES task (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_527EDB25705F2BCA ON task (next_occurrence_id)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE task DROP CONSTRAINT FK_527EDB25705F2BCA');
        $this->addSql('DROP INDEX IDX_527EDB25705F2BCA');
        $this->addSql('ALTER TABLE task DROP next_occurrence_id');
    }
}
