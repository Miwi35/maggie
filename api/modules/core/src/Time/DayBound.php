<?php

declare(strict_types=1);

namespace Maggie\Core\Time;

/**
 * Where a range of instants starts and ends, in days — the one place a day is
 * compared with an instant (MAG-382).
 *
 * An all-day event is a pair of dates, the last one included; a range asked
 * for is a pair of instants, `[from, to)`. The event belongs to the range when
 * its days meet the range's days: from the day `from` falls on to the day of
 * the last instant before `to`. So a month asked as `[1st 00:00, 1st of next
 * month 00:00)` covers the 1st to the 30th, and not the 1st of the next month.
 *
 * The instants are read in the owner's zone. Maggie has one owner and no time
 * zone per user, and the clients send their local midnight: read in UTC, the
 * Paris midnight of the 1st is still the 30th, and the range would gain a day.
 */
final class DayBound
{
    /** Where the owner lives — the default of every agenda and event too. */
    public const string OWNER_TIME_ZONE = 'Europe/Paris';

    /** The first day of a range starting at `$from`: the day it falls on. */
    public static function firstDay(\DateTimeInterface|string $from): \DateTimeImmutable
    {
        return self::dayOf(self::instant($from));
    }

    /** The last day of a range ending, excluded, at `$to`: the day of the instant just before. */
    public static function lastDay(\DateTimeInterface|string $to): \DateTimeImmutable
    {
        return self::dayOf(self::instant($to)->modify('-1 microsecond'));
    }

    /**
     * A filter bound as the clients send it — `after`, `before` and their
     * strict forms — as a comparison on the day field.
     *
     * `after` names where a range starts, `before` where it ends: the strict
     * and the loose forms only differ on the very instant of the bound, and a
     * day has no instant.
     *
     * @return array{0: 'gte'|'lte', 1: \DateTimeImmutable}|null null for an operator that is not a bound
     */
    public static function forOperator(string $operator, string $value): ?array
    {
        return match ($operator) {
            'after', 'strictly_after' => ['gte', self::firstDay($value)],
            'before', 'strictly_before' => ['lte', self::lastDay($value)],
            default => null,
        };
    }

    private static function instant(\DateTimeInterface|string $value): \DateTimeImmutable
    {
        $zone = new \DateTimeZone(self::OWNER_TIME_ZONE);

        if ($value instanceof \DateTimeInterface) {
            return \DateTimeImmutable::createFromInterface($value)->setTimezone($zone);
        }

        // `new \DateTimeImmutable('')` is now, not an error.
        if ('' === trim($value)) {
            throw new \InvalidArgumentException('An empty date bounds nothing.');
        }

        // A bare day is that day's midnight in the owner's zone; a string with
        // its own offset keeps it, and is then read in the owner's zone.
        return (new \DateTimeImmutable($value, $zone))->setTimezone($zone);
    }

    private static function dayOf(\DateTimeImmutable $instant): \DateTimeImmutable
    {
        return new \DateTimeImmutable($instant->format('Y-m-d'), new \DateTimeZone('UTC'));
    }
}
