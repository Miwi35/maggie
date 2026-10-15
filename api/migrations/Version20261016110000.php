<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;
use Maggie\Grocery\Service\SeasonalityCatalog;

/**
 * The months a product is in season (MAG-494), and once, the backfill of the
 * existing products from the seasonality calendar — what
 * `app:grocery:seasonality:backfill` does, without the Mercure updates: the
 * deploy reindexes Elasticsearch after the migrations. Done here rather than
 * on every deploy, so a season the user emptied later is never filled again.
 *
 * The statements are computed from what the database holds and queued, so a
 * dry run prints them and writes nothing. Every existing product has no months
 * yet, so the column is not read. The calendar is read as it is at migration
 * time: replayed later, with a newer `seasonality.json`, it would fill what
 * that file says.
 */
final class Version20261016110000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add product.season_months and fill it from the seasonality calendar';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE product ADD season_months JSON DEFAULT NULL');

        $catalog = new SeasonalityCatalog();
        $filled = 0;

        foreach ($this->connection->fetchAllAssociative('SELECT id, name, ciqual_alim_code FROM product') as $row) {
            $months = $catalog->monthsFor((string) $row['name'], null !== $row['ciqual_alim_code'] ? (string) $row['ciqual_alim_code'] : null);
            if (null === $months) {
                continue;
            }

            $this->addSql('UPDATE product SET season_months = :months WHERE id = :id', [
                'months' => json_encode($months, JSON_THROW_ON_ERROR),
                'id' => $row['id'],
            ]);
            ++$filled;
        }

        $this->write(sprintf('MAG-494: %d product(s) get their season months from the calendar.', $filled));
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE product DROP season_months');
    }
}
