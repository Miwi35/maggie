<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * An agenda a module keeps for itself is recognised by an attribute, not by its
 * name (MAG-324): `module = 'cookbook'` is the meals' agenda, whatever the user
 * renamed it to. A user holds at most one per module.
 *
 * Schema only. Filing the meals that already exist is
 * `app:cookbook:file-meals-in-module-agenda`, which the deploy runs right after
 * the migrations: removing a meal's copy from Google needs the application.
 */
final class Version20261007230000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Let a module keep an internal agenda: agenda.module and one per user and module';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE agenda ADD module VARCHAR(50) DEFAULT NULL');
        // NULLs are distinct in Postgres: ordinary agendas do not collide.
        $this->addSql('CREATE UNIQUE INDEX uniq_agenda_user_module ON agenda (user_id, module)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('DROP INDEX uniq_agenda_user_module');
        $this->addSql('ALTER TABLE agenda DROP module');
    }
}
