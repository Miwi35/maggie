<?php

namespace Maggie\Calendar\Mcp;

use Maggie\Calendar\Entity\Event;

/**
 * The schedule of an event as `create_event` and `update_event` take it (MAG-321): a start and
 * an end, never a duration, in one of two complete forms.
 *
 * - timed: `start_date` + `start_time` + `end_date` + `end_time`;
 * - all day: `all_day: true` + `start_date` + `end_date`, the end excluded as in Google's API
 *   (the 1st alone ends on the 2nd — MAG-382).
 *
 * Nothing is deduced from the event or from "now": a part left out is refused, so that what
 * Maggie announces is what she asked for.
 */
final readonly class EventSchedule
{
    /**
     * A timed schedule has the two instants, an all-day one the two days, the end
     * excluded — never both (MAG-382).
     */
    private function __construct(
        public bool $allDay,
        public ?\DateTimeImmutable $startAt = null,
        public ?\DateTimeImmutable $endAt = null,
        public ?\DateTimeImmutable $startDate = null,
        public ?\DateTimeImmutable $endDate = null,
    ) {
    }

    /**
     * @return self|null null when the caller did not touch the schedule at all
     *
     * @throws \DomainException when the schedule is incomplete, malformed or ends before it starts
     */
    public static function fromParts(
        ?string $startDate,
        ?string $startTime,
        ?string $endDate,
        ?string $endTime,
        ?bool $allDay,
        \DateTimeZone $zone,
    ): ?self {
        [$startDate, $startTime, $endDate, $endTime] = array_map(
            static fn (?string $part) => null === $part || '' === trim($part) ? null : trim($part),
            [$startDate, $startTime, $endDate, $endTime],
        );

        $touched = null !== $startDate || null !== $startTime || null !== $endDate || null !== $endTime || true === $allDay;
        if (!$touched) {
            return null;
        }

        if (true === $allDay) {
            if (null !== $startTime || null !== $endTime) {
                throw new \DomainException('An all-day event has no start_time or end_time: pass all_day true with start_date and end_date only, or drop all_day and give the four of start_date, start_time, end_date and end_time. Nothing was saved.');
            }
            self::requireParts(['start_date' => $startDate, 'end_date' => $endDate]);

            // A day has no zone: read as it is written.
            $utc = new \DateTimeZone('UTC');
            $first = self::day($startDate, 'start_date', $utc);
            $until = self::day($endDate, 'end_date', $utc);
            if ($until <= $first) {
                throw new \DomainException("end_date is excluded, the day after the last day: it must be after start_date, got {$startDate} → {$endDate} (a single day on {$startDate} ends on {$first->modify('+1 day')->format('Y-m-d')}). Nothing was saved.");
            }

            return new self(allDay: true, startDate: $first, endDate: $until);
        }

        self::requireParts([
            'start_date' => $startDate,
            'start_time' => $startTime,
            'end_date' => $endDate,
            'end_time' => $endTime,
        ]);

        $startAt = self::moment($startDate, $startTime, 'start', $zone);
        $endAt = self::moment($endDate, $endTime, 'end', $zone);
        if ($endAt <= $startAt) {
            throw new \DomainException(sprintf('The end must be after the start, got %s → %s. Nothing was saved.', $startAt->format('Y-m-d H:i'), $endAt->format('Y-m-d H:i')));
        }

        return new self(allDay: false, startAt: $startAt, endAt: $endAt);
    }

    /**
     * The same, for a caller that has to give a schedule: giving none is an incomplete one.
     *
     * @throws \DomainException
     */
    public static function required(
        ?string $startDate,
        ?string $startTime,
        ?string $endDate,
        ?string $endTime,
        ?bool $allDay,
        \DateTimeZone $zone,
    ): self {
        $schedule = self::fromParts($startDate, $startTime, $endDate, $endTime, $allDay, $zone);
        if (null === $schedule) {
            self::requireParts(['start_date' => null, 'start_time' => null, 'end_date' => null, 'end_time' => null]);
        }

        return $schedule;
    }

    /**
     * The schedule as the owner reads it — the event's own zone, not the database's — so Maggie
     * announces what was saved and not what she meant to save.
     *
     * @return array<string, mixed>
     */
    public static function describe(Event $event): array
    {
        $zone = self::zoneOf($event);

        $described = [
            'allDay' => $event->isAllDay(),
            'startAt' => $event->getStartAt()?->setTimezone($zone)->format('c'),
            'endAt' => $event->getEndAt()?->setTimezone($zone)->format('c'),
        ];

        if (null !== $event->getStartDate()) {
            // The days as stored, the end excluded, and the last day to announce.
            $until = $event->getEndDate() ?? $event->getStartDate()->modify('+1 day');
            $described['startDate'] = $event->getStartDate()->format('Y-m-d');
            $described['endDate'] = $until->format('Y-m-d');
            $described['lastDay'] = $until->modify('-1 day')->format('Y-m-d');
        }

        return $described + ['timeZone' => $zone->getName()];
    }

    /** The zone the event is read in: its own, or the default one when the row holds a name nobody can resolve. */
    public static function zoneOf(Event $event): \DateTimeZone
    {
        try {
            return new \DateTimeZone($event->getTimeZone());
        } catch (\Exception) {
            return new \DateTimeZone(Event::FALLBACK_TIME_ZONE);
        }
    }

    /** @param array<string, string|null> $parts */
    private static function requireParts(array $parts): void
    {
        $missing = array_keys(array_filter($parts, static fn (?string $part) => null === $part));
        if ([] === $missing) {
            return;
        }

        throw new \DomainException(sprintf('The schedule is incomplete, missing: %s. Give a start and an end — start_date, start_time, end_date and end_time, or all_day true with start_date and end_date (excluded, the day after the last) — never a duration. Nothing was saved.', implode(', ', $missing)));
    }

    private static function day(string $value, string $name, \DateTimeZone $zone): \DateTimeImmutable
    {
        $day = \DateTimeImmutable::createFromFormat('!Y-m-d', $value, $zone);
        if (false === $day || 0 !== (\DateTimeImmutable::getLastErrors()['warning_count'] ?? 0) || $day->format('Y-m-d') !== $value) {
            throw new \DomainException("{$name} must be a date as YYYY-MM-DD, got \"{$value}\". Nothing was saved.");
        }

        return $day;
    }

    private static function moment(string $date, string $time, string $side, \DateTimeZone $zone): \DateTimeImmutable
    {
        $day = self::day($date, "{$side}_date", $zone);

        $parsed = \DateTimeImmutable::createFromFormat('!H:i', $time, $zone);
        if (false === $parsed || 0 !== (\DateTimeImmutable::getLastErrors()['warning_count'] ?? 0) || $parsed->format('H:i') !== $time) {
            throw new \DomainException("{$side}_time must be a time as HH:MM, got \"{$time}\". Nothing was saved.");
        }

        return $day->setTime((int) $parsed->format('H'), (int) $parsed->format('i'));
    }
}
