<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Enum\DayRule;

/**
 * When a recurring operation falls due. Nothing is stored: every occurrence is
 * computed from the anchor, on demand.
 *
 * The n-th occurrence is the anchor plus n steps, never the previous
 * occurrence plus one: anchored on 31 January, the series gives 28 February
 * then 31 March, not 28 March. Days are compared as calendar days — a time
 * of day in an argument is ignored — and a window [from, to) includes its
 * first day and excludes its last.
 */
class RecurrenceSchedule
{
    /**
     * The occurrences that fall in [from, to), in order.
     *
     * @return list<\DateTimeImmutable>
     */
    public function occurrencesBetween(RecurringOperation $operation, \DateTimeInterface $from, \DateTimeInterface $to): array
    {
        $first = max($from->format('Y-m-d'), $operation->getAnchorOn()->format('Y-m-d'));
        $last = $to->format('Y-m-d');

        $occurrences = [];
        for ($n = max(0, $this->stepsBefore($operation, $first) - 1);; ++$n) {
            $day = $this->dayOf($operation, $n);

            if ($day >= $last || !$this->runsOn($operation, $day)) {
                break;
            }

            if ($day >= $first) {
                $occurrences[] = $this->toDate($operation, $day);
            }
        }

        return $occurrences;
    }

    /**
     * The occurrence a day belongs to: the nearest one, the earlier on a tie.
     * Null when the series has none — ended before, or not a single one yet.
     */
    public function occurrenceFor(RecurringOperation $operation, \DateTimeInterface $day): ?\DateTimeImmutable
    {
        $target = $day->format('Y-m-d');
        $estimate = $this->stepsBefore($operation, $target);

        $best = null;
        $bestDistance = null;
        foreach ([$estimate - 1, $estimate, $estimate + 1] as $n) {
            if ($n < 0) {
                continue;
            }

            $candidate = $this->dayOf($operation, $n);
            if (!$this->runsOn($operation, $candidate)) {
                continue;
            }

            $distance = $this->daysBetween($candidate, $target);
            if (null === $bestDistance || $distance < $bestDistance) {
                $best = $candidate;
                $bestDistance = $distance;
            }
        }

        return null === $best ? null : $this->toDate($operation, $best);
    }

    /** The first occurrence strictly after a day, or null once the series has ended. */
    public function nextOccurrenceAfter(RecurringOperation $operation, \DateTimeInterface $day): ?\DateTimeImmutable
    {
        $after = $day->format('Y-m-d');

        for ($n = max(0, $this->stepsBefore($operation, $after) - 1);; ++$n) {
            $candidate = $this->dayOf($operation, $n);

            if (!$this->runsOn($operation, $candidate)) {
                return null;
            }

            if ($candidate > $after) {
                return $this->toDate($operation, $candidate);
            }
        }
    }

    /** The n-th occurrence (0 is the anchor's), as Y-m-d. */
    private function dayOf(RecurringOperation $operation, int $n): string
    {
        $anchor = $operation->getAnchorOn();
        $months = $operation->getPeriod()->months();

        if (null === $months) {
            return $this->utc($anchor->format('Y-m-d'))->modify(sprintf('+%d days', 7 * $n))->format('Y-m-d');
        }

        $index = (int) $anchor->format('Y') * 12 + (int) $anchor->format('n') - 1 + $n * $months;
        $year = intdiv($index, 12);
        $month = $index % 12 + 1;
        $length = (int) $this->utc(sprintf('%04d-%02d-01', $year, $month))->format('t');

        $dayOfMonth = DayRule::LastDayOfMonth === $operation->getDayRule()
            ? $length
            : min((int) $anchor->format('j'), $length);

        return sprintf('%04d-%02d-%02d', $year, $month, $dayOfMonth);
    }

    /** Whole steps from the anchor to a day — an index never past the occurrence of that day. */
    private function stepsBefore(RecurringOperation $operation, string $day): int
    {
        $anchor = $operation->getAnchorOn()->format('Y-m-d');
        if ($day <= $anchor) {
            return 0;
        }

        $months = $operation->getPeriod()->months();
        if (null === $months) {
            return intdiv($this->daysBetween($anchor, $day), 7);
        }

        [$anchorYear, $anchorMonth] = array_map('intval', explode('-', $anchor));
        [$year, $month] = array_map('intval', explode('-', $day));

        return intdiv(($year - $anchorYear) * 12 + $month - $anchorMonth, $months);
    }

    private function runsOn(RecurringOperation $operation, string $day): bool
    {
        $endsOn = $operation->getEndsOn();

        return null === $endsOn || $day <= $endsOn->format('Y-m-d');
    }

    private function daysBetween(string $a, string $b): int
    {
        return (int) $this->utc($a)->diff($this->utc($b))->days;
    }

    /** Calendar arithmetic in UTC, where no day is 23 or 25 hours long. */
    private function utc(string $day): \DateTimeImmutable
    {
        return new \DateTimeImmutable($day, new \DateTimeZone('UTC'));
    }

    private function toDate(RecurringOperation $operation, string $day): \DateTimeImmutable
    {
        return new \DateTimeImmutable($day, $operation->getAnchorOn()->getTimezone());
    }
}
