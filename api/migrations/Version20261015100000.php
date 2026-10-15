<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Seasonal amounts of a monthly envelope (MAG-415): Cinéma 45 € from November
 * to February, 20 € the rest of the time. `envelope.seasonal_amounts` lists
 * the seasons as `{fromMonth, toMonth, amountCents}`; an envelope without any
 * keeps a single amount, so every existing row gets an empty list.
 */
final class Version20261015100000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add envelope.seasonal_amounts';
    }

    public function up(Schema $schema): void
    {
        $this->addSql("ALTER TABLE envelope ADD seasonal_amounts JSON DEFAULT '[]' NOT NULL");
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE envelope DROP seasonal_amounts');
    }
}
