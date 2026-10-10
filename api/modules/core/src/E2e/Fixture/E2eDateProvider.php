<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Fixture;

/**
 * Turns "+2 days 09:00" in a fixture file into a real date, counted from an
 * anchor the seed command chooses.
 *
 * E2E data has to satisfy two things that pull against each other: a journey
 * asserting "the next 7 days" needs dates near today, and a journey asserting
 * an exact value needs the same date on every run. Hard-coded dates give the
 * second and lose the first; `+2 days` gives the first and loses the second
 * the moment a run straddles midnight or a month boundary.
 *
 * Anchoring gives both. Every fixture date is an offset from one instant, and
 * the run chooses that instant: today at midnight in the test user's time zone
 * by default, whatever CI pins with `--now` when a test needs a specific weekday
 * or month.
 *
 * The time zone is Europe/Paris, not UTC, because that is the day the dashboard,
 * the reminders and the daily score reason in. Anchored on UTC midnight, the
 * "today" of the seed is still yesterday for the user between 00:00 and 02:00
 * in Paris, and the lunch of the day is not on the dashboard.
 */
final class E2eDateProvider
{
    public const TIMEZONE = 'Europe/Paris';

    private \DateTimeImmutable $anchor;

    public function __construct()
    {
        $this->anchor = self::anchorFor(new \DateTimeImmutable());
    }

    /** Midnight, in the test user's time zone, of the day that instant falls on there. */
    public static function anchorFor(\DateTimeImmutable $instant): \DateTimeImmutable
    {
        return $instant->setTimezone(new \DateTimeZone(self::TIMEZONE))->setTime(0, 0);
    }

    public function setAnchor(\DateTimeImmutable $anchor): void
    {
        $this->anchor = $anchor;
    }

    public function getAnchor(): \DateTimeImmutable
    {
        return $this->anchor;
    }

    /**
     * @param string $modifier anything `DateTimeImmutable::modify()` accepts,
     *                         e.g. "+1 day 09:00", "first day of this month"
     * @param string ...$then  more modifiers, applied one after the other: PHP
     *                         applies `first day of` after every other part of
     *                         one modifier, so the 2nd of the month is
     *                         ("first day of this month", "+1 day")
     */
    public function e2eDate(string $modifier = '', string ...$then): \DateTimeImmutable
    {
        $date = $this->anchor;

        foreach (array_filter([$modifier, ...$then], static fn (string $m): bool => '' !== $m) as $step) {
            try {
                // A typo in a fixture file has to stop the seed here, rather than
                // quietly hand back the anchor and leave a journey asserting the
                // wrong day.
                $date = $date->modify($step);
            } catch (\Throwable $e) {
                throw new \InvalidArgumentException(sprintf('Cannot apply "%s" to the e2e anchor.', $step), 0, $e);
            }
        }

        return $date;
    }

    public function e2eDateString(string $modifier = '', string $format = 'Y-m-d'): string
    {
        return $this->e2eDate($modifier)->format($format);
    }

    /** Year of the anchor, for envelopes and any other year-keyed row. */
    public function e2eYear(string $modifier = ''): int
    {
        return (int) $this->e2eDate($modifier)->format('Y');
    }

    public function e2eMonth(string $modifier = ''): int
    {
        return (int) $this->e2eDate($modifier)->format('n');
    }
}
