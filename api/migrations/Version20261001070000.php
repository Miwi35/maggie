<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * One default agenda per user (MAG-149).
 *
 * Marking an agenda as the default never took the flag from the others, so a
 * user can hold several. The most recently created one — the latest choice —
 * keeps it; the index then keeps it from happening again.
 */
final class Version20261001070000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Keep a user from having more than one default agenda';
    }

    public function up(Schema $schema): void
    {
        // ULIDs sort by creation time, and Postgres has no max() on uuid, so
        // the id is compared as text.
        $this->addSql(<<<'SQL'
            UPDATE agenda SET is_default = false
            WHERE is_default = true
              AND id::text < (
                SELECT MAX(other.id::text) FROM agenda other
                WHERE other.user_id = agenda.user_id AND other.is_default = true
              )
            SQL);
        $this->addSql('CREATE UNIQUE INDEX uniq_agenda_user_default ON agenda (user_id) WHERE (is_default = true)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('DROP INDEX uniq_agenda_user_default');
    }
}
