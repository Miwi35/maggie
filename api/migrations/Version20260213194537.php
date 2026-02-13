<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20260213194537 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add user ownership to agenda and task entities';
    }

    public function up(Schema $schema): void
    {
        // Add nullable user_id columns first
        $this->addSql('ALTER TABLE agenda ADD user_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE task ADD user_id UUID DEFAULT NULL');

        // Assign existing rows to the first user
        $this->addSql('UPDATE agenda SET user_id = (SELECT id FROM "user" LIMIT 1) WHERE user_id IS NULL');
        $this->addSql('UPDATE task SET user_id = (SELECT id FROM "user" LIMIT 1) WHERE user_id IS NULL');

        // Make columns NOT NULL
        $this->addSql('ALTER TABLE agenda ALTER COLUMN user_id SET NOT NULL');
        $this->addSql('ALTER TABLE task ALTER COLUMN user_id SET NOT NULL');

        // Add foreign keys and indexes
        $this->addSql('ALTER TABLE agenda ADD CONSTRAINT FK_2CEDC877A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_2CEDC877A76ED395 ON agenda (user_id)');
        $this->addSql('ALTER TABLE task ADD CONSTRAINT FK_527EDB25A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_527EDB25A76ED395 ON task (user_id)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE agenda DROP CONSTRAINT FK_2CEDC877A76ED395');
        $this->addSql('DROP INDEX IDX_2CEDC877A76ED395');
        $this->addSql('ALTER TABLE agenda DROP user_id');
        $this->addSql('ALTER TABLE task DROP CONSTRAINT FK_527EDB25A76ED395');
        $this->addSql('DROP INDEX IDX_527EDB25A76ED395');
        $this->addSql('ALTER TABLE task DROP user_id');
    }
}
