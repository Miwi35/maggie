<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Investment projects (MAG-557): the rent and the planned resale of a project,
 * and the project a funding source's resale comes from.
 */
final class Version20261023120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add project_investment and the resale source of a funding source';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE project_investment (id UUID NOT NULL, monthly_rent_cents INT DEFAULT 0 NOT NULL, resale_on DATE DEFAULT NULL, resale_net_cents INT DEFAULT NULL, project_id UUID NOT NULL, resale_target_id UUID DEFAULT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX UNIQ_5B6C71E4166D1F9C ON project_investment (project_id)');
        $this->addSql('CREATE INDEX IDX_5B6C71E437EC6AFC ON project_investment (resale_target_id)');
        $this->addSql('ALTER TABLE project_investment ADD CONSTRAINT FK_5B6C71E4166D1F9C FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE project_investment ADD CONSTRAINT FK_5B6C71E437EC6AFC FOREIGN KEY (resale_target_id) REFERENCES project (id) ON DELETE SET NULL NOT DEFERRABLE');

        $this->addSql('ALTER TABLE project_funding_source ADD source_project_id UUID DEFAULT NULL');
        $this->addSql('CREATE INDEX IDX_920E87616AD96EB8 ON project_funding_source (source_project_id)');
        $this->addSql('ALTER TABLE project_funding_source ADD CONSTRAINT FK_920E87616AD96EB8 FOREIGN KEY (source_project_id) REFERENCES project (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE project_funding_source DROP CONSTRAINT FK_920E87616AD96EB8');
        $this->addSql('DROP INDEX IDX_920E87616AD96EB8');
        $this->addSql('ALTER TABLE project_funding_source DROP source_project_id');

        $this->addSql('ALTER TABLE project_investment DROP CONSTRAINT FK_5B6C71E4166D1F9C');
        $this->addSql('ALTER TABLE project_investment DROP CONSTRAINT FK_5B6C71E437EC6AFC');
        $this->addSql('DROP TABLE project_investment');
    }
}
