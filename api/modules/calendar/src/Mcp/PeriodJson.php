<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp;

use Maggie\Calendar\Entity\Period;
use Maggie\Core\Time\Weekday;

/**
 * A period as `manage_periods` returns it (MAG-809), in its own zone: the instants
 * of a timed one, or the days of an all-day one with the end excluded and the last
 * day to announce — each with its day of the week, as `create_event` gives them.
 */
final class PeriodJson
{
    /** @return array<string, mixed> */
    public static function of(Period $period): array
    {
        $zone = $period->zone();
        $json = [
            'id' => (string) $period->getId(),
            'name' => $period->getName(),
            'agenda' => $period->getAgenda()->getName(),
            'agendaId' => (string) $period->getAgenda()->getId(),
            'allDay' => $period->isAllDay(),
        ];

        $startAt = $period->getStartAt()?->setTimezone($zone);
        $endAt = $period->getEndAt()?->setTimezone($zone);
        if (null !== $startAt && null !== $endAt) {
            $json['startAt'] = $startAt->format(\DATE_ATOM);
            $json['startWeekday'] = Weekday::fr($startAt);
            $json['endAt'] = $endAt->format(\DATE_ATOM);
            $json['endWeekday'] = Weekday::fr($endAt);
        }

        $startDate = $period->getStartDate();
        if (null !== $startDate) {
            $until = $period->getEndDate() ?? $startDate->modify('+1 day');
            $lastDay = $until->modify('-1 day');
            $json['startDate'] = $startDate->format('Y-m-d');
            $json['startWeekday'] = Weekday::fr($startDate);
            $json['endDate'] = $until->format('Y-m-d');
            $json['lastDay'] = $lastDay->format('Y-m-d');
            $json['lastDayWeekday'] = Weekday::fr($lastDay);
        }

        return $json + [
            'timeZone' => $zone->getName(),
            'rrule' => $period->getRrule(),
            'holiday' => $period->getHoliday()?->value,
        ];
    }
}
