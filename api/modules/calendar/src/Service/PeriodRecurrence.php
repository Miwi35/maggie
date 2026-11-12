<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Period;
use Maggie\Core\Time\DayBound;
use Recurr\Rule;
use Recurr\Transformer\ArrayTransformer;
use Recurr\Transformer\ArrayTransformerConfig;

/**
 * The occurrences of the periods meeting a range (MAG-809): {@see RecurrenceService}'s
 * expansion, for a period — no exception instance, no cancelled status. That one is
 * typed {@see \Maggie\Calendar\Entity\Event} and reads the exceptions, so it stays as it is.
 *
 * An occurrence is a clone of its series, not persisted, with its own schedule and the
 * series' id.
 */
class PeriodRecurrence
{
    private ArrayTransformer $transformer;

    public function __construct()
    {
        $config = new ArrayTransformerConfig();
        $config->enableLastDayOfMonthFix();
        $this->transformer = new ArrayTransformer();
        $this->transformer->setConfig($config);
    }

    /**
     * The periods meeting `[rangeStart, rangeEnd)`, each series unfolded, by start.
     *
     * @param iterable<Period> $periods
     *
     * @return list<Period>
     */
    public function expandAll(iterable $periods, \DateTimeImmutable $rangeStart, \DateTimeImmutable $rangeEnd): array
    {
        $expanded = [];
        foreach ($periods as $period) {
            if ($period->isRecurring()) {
                array_push($expanded, ...$this->expandOccurrences($period, $rangeStart, $rangeEnd));
            } elseif (self::meets($period, $rangeStart, $rangeEnd)) {
                $expanded[] = $period;
            }
        }
        usort($expanded, static fn (Period $a, Period $b): int => $a->getStartInstant() <=> $b->getStartInstant());

        return $expanded;
    }

    /** @return list<Period> */
    public function expandOccurrences(Period $period, \DateTimeImmutable $rangeStart, \DateTimeImmutable $rangeEnd): array
    {
        if (!$period->isRecurring()) {
            return [$period];
        }

        if (null !== $period->getStartDate()) {
            return $this->expandDays($period, $rangeStart, $rangeEnd);
        }

        // On the period's own wall clock: in UTC a fortnightly 18:00 Paris custody
        // would drift an hour when the clocks change.
        $zone = $period->zone();
        $first = $period->getStartInstant();
        $duration = $first->diff($period->getEndInstant());

        $occurrences = [];
        foreach ($this->transformer->transform(new Rule($period->getRrule(), $first->setTimezone($zone), null, $zone->getName())) as $recurrence) {
            // Same instant, same offset notation as the series: callers see no change of shape.
            $start = \DateTimeImmutable::createFromInterface($recurrence->getStart())->setTimezone($first->getTimezone());
            if ($start >= $rangeEnd) {
                break;
            }
            $end = $start->add($duration);
            if ($end <= $rangeStart) {
                continue;
            }

            $occurrence = clone $period;
            $occurrence->setStartAt($start);
            $occurrence->setEndAt($end);
            $occurrences[] = $occurrence;
        }

        return $occurrences;
    }

    /**
     * An all-day series, unfolded on its days: no zone, so no occurrence slips to the
     * day next door. Each keeps the series' length in days, `[startDate, endDate)`.
     *
     * @return list<Period>
     */
    private function expandDays(Period $period, \DateTimeImmutable $rangeStart, \DateTimeImmutable $rangeEnd): array
    {
        $utc = new \DateTimeZone('UTC');
        $first = new \DateTimeImmutable((string) $period->getStartDate()?->format('Y-m-d'), $utc);
        $lengthInDays = max(1, (int) $first->diff($period->getEndDate() ?? $first)->format('%r%a'));
        $firstDay = DayBound::firstDay($rangeStart);
        $lastDay = DayBound::lastDay($rangeEnd);

        $occurrences = [];
        foreach ($this->transformer->transform(new Rule($period->getRrule(), $first, null, 'UTC')) as $recurrence) {
            $day = new \DateTimeImmutable($recurrence->getStart()->format('Y-m-d'), $utc);
            if ($day > $lastDay) {
                break;
            }
            $end = $day->modify("+{$lengthInDays} days");
            if ($end <= $firstDay) {
                continue;
            }

            $occurrence = clone $period;
            $occurrence->setStartDate($day);
            $occurrence->setEndDate($end);
            $occurrences[] = $occurrence;
        }

        return $occurrences;
    }

    /** A single period in the range: by its instants, or by its days for an all-day one. */
    private static function meets(Period $period, \DateTimeImmutable $rangeStart, \DateTimeImmutable $rangeEnd): bool
    {
        if (null !== $period->getStartAt() && null !== $period->getEndAt()) {
            return $period->getStartAt() < $rangeEnd && $period->getEndAt() > $rangeStart;
        }

        return null !== $period->getStartDate() && null !== $period->getEndDate()
            && $period->getStartDate()->format('Y-m-d') <= DayBound::lastDay($rangeEnd)->format('Y-m-d')
            && $period->getEndDate()->format('Y-m-d') > DayBound::firstDay($rangeStart)->format('Y-m-d');
    }
}
