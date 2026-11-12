<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The savings account a single-legged advance or repayment comes from or goes
 * back to (MAG-806): a livret typed by hand, which no bank sync gives a debit leg.
 */
final class Version20261113150000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add transaction.advance_source_id';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction ADD advance_source_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE transaction ADD CONSTRAINT FK_723705D154D84B97 FOREIGN KEY (advance_source_id) REFERENCES account (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_723705D154D84B97 ON transaction (advance_source_id)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction DROP CONSTRAINT FK_723705D154D84B97');
        $this->addSql('DROP INDEX IDX_723705D154D84B97');
        $this->addSql('ALTER TABLE transaction DROP advance_source_id');
    }
}
