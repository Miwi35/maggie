<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * A meal is a day and a slot (MAG-251).
 *
 * `meal.date` becomes the meal's reference, and it is filled from the instant
 * the meal used to carry, read **in Paris** — which is where the owner plans
 * his week. That one conversion puts back on the right day every meal the week
 * view wrote with a hard-coded `+01:00`: midnight at `+01:00` on an October day
 * is 23:00 the day before in UTC, and anything reading the day in UTC ranged it
 * there. Read in Paris it is 01:00 on the day the owner asked for.
 *
 * The instants then follow the day instead of deciding it: the whole day in
 * Paris, which is what `Meal::setDate()` derives from now on, so the agenda
 * shows the meal where the week view does.
 *
 * It reports what it moved, so the deploy log says how many meals this was
 * about in production and shows a few of them.
 */
final class Version20261005160000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Make a meal a day and a slot: meal.date, filled from the old instant read in Paris';
    }

    public function up(Schema $schema): void
    {
        $this->reportWhatMoves();

        $this->addSql('ALTER TABLE meal ADD date DATE DEFAULT NULL');
        $this->addSql(<<<'SQL'
            UPDATE meal SET date = (event.start_at AT TIME ZONE 'Europe/Paris')::date
            FROM event WHERE event.id = meal.id
            SQL);
        $this->addSql('ALTER TABLE meal ALTER COLUMN date SET NOT NULL');
        $this->addSql('CREATE INDEX idx_meal_date ON meal (date)');

        // The instants are derived now: the whole day in Paris, all-day, as
        // Meal::setDate() builds them.
        $this->addSql(<<<'SQL'
            UPDATE event SET
                start_at = (meal.date::timestamp) AT TIME ZONE 'Europe/Paris',
                end_at = (meal.date::timestamp + INTERVAL '23 hours 59 minutes 59 seconds') AT TIME ZONE 'Europe/Paris',
                all_day = true
            FROM meal WHERE meal.id = event.id
            SQL);
    }

    /**
     * Back to a meal whose day is read off its instant.
     *
     * `start_at` and `end_at` are left as the whole day the day produced — the
     * old code read exactly that, so the previous version works on them. The
     * times the rows held before (19:30 for a dinner, say) are not restored:
     * the owner's decision is that a meal has no time, and reversibility here
     * means the schema and a working previous version, not an hour nothing
     * reads.
     */
    public function down(Schema $schema): void
    {
        $this->addSql('DROP INDEX idx_meal_date');
        $this->addSql('ALTER TABLE meal DROP date');
    }

    /**
     * Counts the meals, and the ones this migration puts on another day, and
     * shows a few of them before and after. Read-only, so it runs on a dry run
     * too.
     */
    private function reportWhatMoves(): void
    {
        $rows = $this->connection->fetchAllAssociative(<<<'SQL'
            SELECT
                event.summary,
                to_char(event.start_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') AS before_utc,
                to_char(event.start_at AT TIME ZONE 'Europe/Paris', 'YYYY-MM-DD') AS after_day,
                (event.start_at AT TIME ZONE 'Europe/Paris')::date
                    <> (event.start_at AT TIME ZONE 'UTC')::date AS moves
            FROM meal JOIN event ON event.id = meal.id
            ORDER BY event.start_at DESC
            SQL);

        $moving = array_values(array_filter($rows, static fn (array $row) => (bool) $row['moves']));

        $this->write(sprintf(
            'MAG-251: %d meal(s), %d of them read a day earlier in UTC than the day the owner asked for.',
            \count($rows),
            \count($moving),
        ));

        foreach (\array_slice([] !== $moving ? $moving : $rows, 0, 5) as $row) {
            $this->write(sprintf(
                '  %s — %s UTC → %s',
                $row['summary'],
                $row['before_utc'],
                $row['after_day'],
            ));
        }
    }
}
