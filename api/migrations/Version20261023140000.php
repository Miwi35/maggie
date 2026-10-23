<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The simulated placement a project's `capital` funding source leaves
 * (MAG-556): a scenario takes that amount out of it the month the project is bought.
 */
final class Version20261023140000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add project_funding_source.placement_id';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE project_funding_source ADD placement_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE project_funding_source ADD CONSTRAINT FK_920E87612F966E9D FOREIGN KEY (placement_id) REFERENCES placement (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_920E87612F966E9D ON project_funding_source (placement_id)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE project_funding_source DROP CONSTRAINT FK_920E87612F966E9D');
        $this->addSql('DROP INDEX IDX_920E87612F966E9D');
        $this->addSql('ALTER TABLE project_funding_source DROP placement_id');
    }
}
