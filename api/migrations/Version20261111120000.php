<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * A planned session's event in the « Santé » module agenda (MAG-777): one per
 * session, forgotten when the event goes.
 */
final class Version20261111120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add workout.event_id';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE workout ADD event_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE workout ADD CONSTRAINT FK_649FFB7271F7E88B FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('CREATE UNIQUE INDEX UNIQ_649FFB7271F7E88B ON workout (event_id)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE workout DROP CONSTRAINT FK_649FFB7271F7E88B');
        $this->addSql('DROP INDEX UNIQ_649FFB7271F7E88B');
        $this->addSql('ALTER TABLE workout DROP event_id');
    }
}
