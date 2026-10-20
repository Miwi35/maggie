<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The user's projects (MAG-519): what they save for, their priority order —
 * unique per user — and the projects each one waits for.
 */
final class Version20261020120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add project and project_dependency tables';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE project (id UUID NOT NULL, name VARCHAR(255) NOT NULL, amount_cents INT NOT NULL, currency VARCHAR(3) NOT NULL, wellbeing VARCHAR(16) NOT NULL, position INT NOT NULL, date_mode VARCHAR(16) NOT NULL, target_on DATE DEFAULT NULL, note TEXT DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_2FB3D0EEA76ED395 ON project (user_id)');
        $this->addSql('CREATE UNIQUE INDEX project_user_position ON project (user_id, position)');
        $this->addSql('ALTER TABLE project ADD CONSTRAINT FK_2FB3D0EEA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('CREATE TABLE project_dependency (project_id UUID NOT NULL, depends_on_id UUID NOT NULL, PRIMARY KEY (project_id, depends_on_id))');
        $this->addSql('CREATE INDEX IDX_37FE2E37166D1F9C ON project_dependency (project_id)');
        $this->addSql('CREATE INDEX IDX_37FE2E371E088F8 ON project_dependency (depends_on_id)');
        $this->addSql('ALTER TABLE project_dependency ADD CONSTRAINT FK_37FE2E37166D1F9C FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE');
        $this->addSql('ALTER TABLE project_dependency ADD CONSTRAINT FK_37FE2E371E088F8 FOREIGN KEY (depends_on_id) REFERENCES project (id) ON DELETE CASCADE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE project_dependency DROP CONSTRAINT FK_37FE2E37166D1F9C');
        $this->addSql('ALTER TABLE project_dependency DROP CONSTRAINT FK_37FE2E371E088F8');
        $this->addSql('DROP TABLE project_dependency');
        $this->addSql('ALTER TABLE project DROP CONSTRAINT FK_2FB3D0EEA76ED395');
        $this->addSql('DROP TABLE project');
    }
}
