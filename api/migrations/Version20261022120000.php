<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The user's simulated placements (MAG-555): an initial payment, a deposit and
 * its frequency, an annual rate in basis points and a duration in years.
 */
final class Version20261022120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add placement table';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE placement (id UUID NOT NULL, name VARCHAR(255) NOT NULL, initial INT NOT NULL, deposit INT NOT NULL, frequency VARCHAR(16) NOT NULL, annual_rate INT NOT NULL, years INT NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_48DB750EA76ED395 ON placement (user_id)');
        $this->addSql('ALTER TABLE placement ADD CONSTRAINT FK_48DB750EA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE placement DROP CONSTRAINT FK_48DB750EA76ED395');
        $this->addSql('DROP TABLE placement');
    }
}
