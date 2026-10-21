<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * A project's funding sources (MAG-524): savings, capital or a projected loan,
 * each gone with its project; a loan source owns its projected loan.
 */
final class Version20261021180000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create project_funding_source';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE project_funding_source (id UUID NOT NULL, kind VARCHAR(16) NOT NULL, amount_cents INT NOT NULL, position INT DEFAULT 0 NOT NULL, project_id UUID NOT NULL, account_id UUID DEFAULT NULL, loan_id UUID DEFAULT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_920E8761166D1F9C ON project_funding_source (project_id)');
        $this->addSql('CREATE INDEX IDX_920E87619B6B5FBA ON project_funding_source (account_id)');
        $this->addSql('CREATE UNIQUE INDEX UNIQ_920E8761CE73868F ON project_funding_source (loan_id)');
        $this->addSql('ALTER TABLE project_funding_source ADD CONSTRAINT FK_920E8761166D1F9C FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE project_funding_source ADD CONSTRAINT FK_920E87619B6B5FBA FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE project_funding_source ADD CONSTRAINT FK_920E8761CE73868F FOREIGN KEY (loan_id) REFERENCES loan (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE project_funding_source DROP CONSTRAINT FK_920E8761166D1F9C');
        $this->addSql('ALTER TABLE project_funding_source DROP CONSTRAINT FK_920E87619B6B5FBA');
        $this->addSql('ALTER TABLE project_funding_source DROP CONSTRAINT FK_920E8761CE73868F');
        $this->addSql('DROP TABLE project_funding_source');
    }
}
