<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** An event has one trip per leg, outbound or return (MAG-734). */
final class Version20261106120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add leg to trip, one trip per event and leg';
    }

    public function up(Schema $schema): void
    {
        $this->addSql("ALTER TABLE trip ADD leg VARCHAR(10) DEFAULT 'outbound' NOT NULL");
        $this->addSql('DROP INDEX UNIQ_7656F53B71F7E88B');
        $this->addSql('CREATE INDEX IDX_7656F53B71F7E88B ON trip (event_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_trip_event_leg ON trip (event_id, leg)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql("DELETE FROM trip WHERE leg = 'return'");
        $this->addSql('DROP INDEX uniq_trip_event_leg');
        $this->addSql('DROP INDEX IDX_7656F53B71F7E88B');
        $this->addSql('CREATE UNIQUE INDEX UNIQ_7656F53B71F7E88B ON trip (event_id)');
        $this->addSql('ALTER TABLE trip DROP leg');
    }
}
