<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * An all-day event is a pair of dates, the last one included (MAG-382).
 *
 * `start_date` and `end_date` are filled for every all-day row, whatever
 * convention wrote its instants — three coexisted, and each display was wrong
 * for at least one of them:
 *
 * - `google`: 00:00Z → 00:00Z the day after the last (Google's exclusive end);
 * - `admin`: 00:00Z → 23:59:59Z of the last day;
 * - `mobile`: midnight in Paris → midnight in Paris after the last day;
 * - `meal`: a meal's own `date`, which is what it always meant;
 * - `other`: anything else, read in Paris like the mobile rows.
 *
 * One rule covers the first four: the first day is the day `start_at` falls on,
 * the last one the day of the second before `end_at`, both read in the zone the
 * convention wrote in. `start_at` and `end_at` are then emptied: a day has no
 * instant.
 *
 * Dry run first: `doctrine:migrations:migrate --dry-run` prints the count per
 * convention and a few rows of each, without writing. The exceptions of an
 * all-day series get their occurrence key re-read the same way: the midnight
 * UTC of the day they replace. The conversion only takes
 * the all-day rows with no date yet, and the schema statements are guarded, so
 * running it again changes nothing.
 */
final class Version20261010040000 extends AbstractMigration
{
    /** The convention of each all-day row not converted yet. */
    private const CLASSIFIED = <<<'SQL'
        SELECT
            e.id,
            CASE
                WHEN m.id IS NOT NULL THEN 'meal'
                WHEN (e.start_at AT TIME ZONE 'UTC')::time = '00:00'
                    AND (e.end_at AT TIME ZONE 'UTC')::time = '00:00' THEN 'google'
                WHEN (e.start_at AT TIME ZONE 'UTC')::time = '00:00'
                    AND (e.end_at AT TIME ZONE 'UTC')::time >= '23:59:59' THEN 'admin'
                WHEN (e.start_at AT TIME ZONE 'Europe/Paris')::time = '00:00' THEN 'mobile'
                ELSE 'other'
            END AS convention
        FROM event e
        LEFT JOIN meal m ON m.id = e.id
        WHERE e.all_day AND e.start_date IS NULL AND e.start_at IS NOT NULL
        SQL;

    public function getDescription(): string
    {
        return 'Make an all-day event a pair of dates: event.start_date and event.end_date (the last day included), start_at and end_at emptied';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE event ADD COLUMN IF NOT EXISTS start_date DATE DEFAULT NULL');
        $this->addSql('ALTER TABLE event ADD COLUMN IF NOT EXISTS end_date DATE DEFAULT NULL');
        $this->addSql('ALTER TABLE event ALTER COLUMN start_at DROP NOT NULL');
        $this->addSql('ALTER TABLE event ALTER COLUMN end_at DROP NOT NULL');
        $this->addSql('CREATE INDEX IF NOT EXISTS idx_event_days ON event (start_date, end_date)');

        $this->reportConventions();

        // The exceptions of an all-day series first, while their series still
        // has its instants to classify: an occurrence is known to its
        // exception by the midnight UTC of its day now, and a series the
        // mobile wrote keyed it at midnight in Paris — 23:00Z the day before,
        // which would cancel or replace the wrong day.
        $this->addSql(sprintf(<<<'SQL'
            WITH classified AS (%s)
            UPDATE event SET original_start_at = (
                (event.original_start_at AT TIME ZONE CASE WHEN c.convention IN ('google', 'admin') THEN 'UTC' ELSE 'Europe/Paris' END)::date
            )::timestamp AT TIME ZONE 'UTC'
            FROM classified c
            WHERE event.recurring_event_id = c.id AND event.original_start_at IS NOT NULL
            SQL, self::CLASSIFIED));

        $this->addSql(sprintf(<<<'SQL'
            WITH classified AS (%s), zoned AS (
                SELECT
                    c.id,
                    c.convention,
                    CASE WHEN c.convention IN ('google', 'admin') THEN 'UTC' ELSE 'Europe/Paris' END AS zone
                FROM classified c
            )
            UPDATE event SET
                start_date = CASE
                    WHEN z.convention = 'meal' THEN (SELECT m.date FROM meal m WHERE m.id = event.id)
                    ELSE (event.start_at AT TIME ZONE z.zone)::date
                END,
                end_date = CASE
                    WHEN z.convention = 'meal' THEN (SELECT m.date FROM meal m WHERE m.id = event.id)
                    ELSE GREATEST(
                        (event.start_at AT TIME ZONE z.zone)::date,
                        ((event.end_at - INTERVAL '1 second') AT TIME ZONE z.zone)::date
                    )
                END
            FROM zoned z
            WHERE z.id = event.id
            SQL, self::CLASSIFIED));

        $this->addSql('UPDATE event SET start_at = NULL, end_at = NULL WHERE all_day AND start_date IS NOT NULL AND (start_at IS NOT NULL OR end_at IS NOT NULL)');
    }

    /**
     * Back to instants, in Google's convention — the one the previous version's
     * sync wrote and read. The days are kept until the columns go, so a row is
     * never left with neither.
     */
    public function down(Schema $schema): void
    {
        $this->addSql(<<<'SQL'
            UPDATE event SET
                start_at = start_date::timestamp AT TIME ZONE 'UTC',
                end_at = (COALESCE(end_date, start_date) + 1)::timestamp AT TIME ZONE 'UTC'
            WHERE start_date IS NOT NULL AND start_at IS NULL
            SQL);
        $this->addSql('ALTER TABLE event ALTER COLUMN start_at SET NOT NULL');
        $this->addSql('ALTER TABLE event ALTER COLUMN end_at SET NOT NULL');
        $this->addSql('DROP INDEX IF EXISTS idx_event_days');
        $this->addSql('ALTER TABLE event DROP COLUMN IF EXISTS start_date');
        $this->addSql('ALTER TABLE event DROP COLUMN IF EXISTS end_date');
    }

    /**
     * The count per convention, and a few rows of each before and after.
     * Read-only, so it runs on a dry run too — and before the columns exist,
     * which is why it reads `start_date` only when it is there.
     */
    private function reportConventions(): void
    {
        $hasDates = (bool) $this->connection->fetchOne(<<<'SQL'
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_name = 'event' AND column_name = 'start_date' AND table_schema = current_schema()
            SQL);
        $classified = $hasDates
            ? self::CLASSIFIED
            : str_replace(' AND e.start_date IS NULL', '', self::CLASSIFIED);

        $rows = $this->connection->fetchAllAssociative(<<<SQL
            SELECT c.convention, e.summary,
                to_char(e.start_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') AS start_utc,
                to_char(e.end_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS end_utc
            FROM ({$classified}) c JOIN event e ON e.id = c.id
            ORDER BY c.convention, e.start_at
            SQL);

        $byConvention = [];
        foreach ($rows as $row) {
            $byConvention[$row['convention']][] = $row;
        }

        $this->write(sprintf('MAG-382: %d all-day event(s) to convert to dates.', \count($rows)));
        foreach (['google', 'admin', 'mobile', 'meal', 'other'] as $convention) {
            $found = $byConvention[$convention] ?? [];
            $this->write(sprintf('  %s: %d', $convention, \count($found)));
            foreach (\array_slice($found, 0, 3) as $row) {
                $this->write(sprintf('    %s — %s → %s UTC', $row['summary'], $row['start_utc'], $row['end_utc']));
            }
        }
    }
}
