<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Period;

/** What the day is, for `get_day_context` (MAG-811): weekend or not, and the periods covering it. */
final readonly class DayContextView
{
    /** @param list<Period> $periods the occurrences covering the day, by start */
    public function __construct(
        public string $date,
        public bool $weekend,
        public array $periods,
    ) {
    }

    /** @return array{date: string, weekend: bool, periods: list<array<string, mixed>>} */
    public function toArray(): array
    {
        return [
            'date' => $this->date,
            'weekend' => $this->weekend,
            'periods' => array_map(self::period(...), $this->periods),
        ];
    }

    /**
     * The bounds are the occurrence's own: instants for a timed one, days — the end included — for an all-day one.
     *
     * @return array<string, mixed>
     */
    private static function period(Period $period): array
    {
        $agenda = $period->getAgenda();
        $allDay = $period->isAllDay();
        $zone = $period->zone();

        if ($allDay) {
            $start = $period->getStartDate();
            $end = $period->getEndDate()?->modify('-1 day') ?? $start;
            $bounds = [$start?->format('Y-m-d'), $end?->format('Y-m-d')];
        } else {
            $bounds = [
                $period->getStartAt()?->setTimezone($zone)->format(\DATE_ATOM),
                $period->getEndAt()?->setTimezone($zone)->format(\DATE_ATOM),
            ];
        }

        return [
            'id' => (string) $period->getId(),
            'name' => $period->getName(),
            'agenda' => ['id' => (string) $agenda->getId(), 'name' => $agenda->getName()],
            'allDay' => $allDay,
            'start' => $bounds[0],
            'end' => $bounds[1],
        ];
    }
}
