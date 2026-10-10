<?php

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Core\Time\DayBound;
use Psr\Log\LoggerInterface;
use Recurr\Rule;
use Recurr\Transformer\ArrayTransformer;
use Recurr\Transformer\ArrayTransformerConfig;

class RecurrenceService
{
    private ArrayTransformer $transformer;

    public function __construct(
        private readonly EventRepository $eventRepository,
        private readonly LoggerInterface $logger,
    ) {
        $config = new ArrayTransformerConfig();
        $config->enableLastDayOfMonthFix();
        $this->transformer = new ArrayTransformer();
        $this->transformer->setConfig($config);
    }

    /**
     * Expand a recurring event into individual occurrences within a date range.
     * Returns virtual Event objects (not persisted) for each occurrence.
     *
     * @return Event[]
     */
    public function expandOccurrences(Event $event, \DateTimeImmutable $rangeStart, \DateTimeImmutable $rangeEnd): array
    {
        if (!$event->isRecurring()) {
            return [$event];
        }

        if (null !== $event->getStartDate()) {
            return $this->expandDays($event, $rangeStart, $rangeEnd);
        }

        // Expand on the event's own wall clock: in UTC a weekly 18:00 Paris series
        // would drift an hour when the clocks change.
        $timeZone = $this->resolveTimeZone($event);
        $start = $event->getStartInstant()->setTimezone($timeZone);

        $rule = new Rule($event->getRrule(), $start, null, $timeZone->getName());

        $recurrences = $this->transformer->transform($rule);

        // Get exceptions for this recurring event
        $exceptions = $this->eventRepository->findExceptionsForRecurringEvent($event);
        $exceptionDates = [];
        foreach ($exceptions as $exception) {
            if (null !== $exception->getOriginalStartAt()) {
                $exceptionDates[$exception->getOriginalStartAt()->getTimestamp()] = $exception;
            }
        }

        $duration = $event->getStartInstant()->diff($event->getEndInstant());
        $occurrences = [];

        foreach ($recurrences as $recurrence) {
            $start = $recurrence->getStart();
            $occurrenceStart = $start instanceof \DateTime
                ? \DateTimeImmutable::createFromMutable($start)
                : \DateTimeImmutable::createFromInterface($start);
            // Same instant, same offset notation as the master: callers see no change of shape.
            $occurrenceStart = $occurrenceStart->setTimezone($event->getStartInstant()->getTimezone());

            // Skip occurrences outside range
            if ($occurrenceStart >= $rangeEnd) {
                break;
            }
            $occurrenceEnd = $occurrenceStart->add($duration);
            if ($occurrenceEnd <= $rangeStart) {
                continue;
            }

            // Check if this occurrence has an exception
            $key = $occurrenceStart->getTimestamp();
            if (isset($exceptionDates[$key])) {
                $exception = $exceptionDates[$key];
                if (EventStatus::Cancelled !== $exception->getStatus()) {
                    $occurrences[] = $exception;
                }
                continue;
            }

            // Create a virtual occurrence
            $occurrence = clone $event;
            $occurrence->setStartAt($occurrenceStart);
            $occurrence->setEndAt($occurrenceEnd);
            $occurrences[] = $occurrence;
        }

        return $occurrences;
    }

    /**
     * An all-day series, expanded on its days (MAG-382): no zone, so no
     * occurrence can slip to the day next door. Each occurrence keeps the
     * master's length in days.
     *
     * An occurrence is known to its exceptions by the midnight UTC of its day:
     * `originalStartAt` is an instant, and that one names a day without
     * reading it anywhere.
     *
     * @return Event[]
     */
    private function expandDays(Event $event, \DateTimeImmutable $rangeStart, \DateTimeImmutable $rangeEnd): array
    {
        $utc = new \DateTimeZone('UTC');
        $first = new \DateTimeImmutable((string) $event->getStartDate()?->format('Y-m-d'), $utc);
        $lengthInDays = (int) $first->diff($event->getEndDate() ?? $first)->format('%r%a');
        $firstDay = DayBound::firstDay($rangeStart);
        $lastDay = DayBound::lastDay($rangeEnd);

        $exceptionDays = [];
        foreach ($this->eventRepository->findExceptionsForRecurringEvent($event) as $exception) {
            if (null !== $exception->getOriginalStartAt()) {
                $exceptionDays[$exception->getOriginalStartAt()->setTimezone($utc)->format('Y-m-d')] = $exception;
            }
        }

        $occurrences = [];
        foreach ($this->transformer->transform(new Rule($event->getRrule(), $first, null, 'UTC')) as $recurrence) {
            $day = new \DateTimeImmutable($recurrence->getStart()->format('Y-m-d'), $utc);
            if ($day > $lastDay) {
                break;
            }
            $lastOfOccurrence = $day->modify("+{$lengthInDays} days");
            if ($lastOfOccurrence < $firstDay) {
                continue;
            }

            $exception = $exceptionDays[$day->format('Y-m-d')] ?? null;
            if (null !== $exception) {
                if (EventStatus::Cancelled !== $exception->getStatus()) {
                    $occurrences[] = $exception;
                }
                continue;
            }

            $occurrence = clone $event;
            $occurrence->setStartDate($day);
            $occurrence->setEndDate($lastOfOccurrence);
            $occurrences[] = $occurrence;
        }

        return $occurrences;
    }

    /**
     * A row can still hold a time zone nobody resolves (the column was free text
     * before it was validated): one such event must not fail the whole read.
     * An error, not a warning, so the row gets fixed; `event` is the stable key.
     */
    private function resolveTimeZone(Event $event): \DateTimeZone
    {
        try {
            return new \DateTimeZone($event->getTimeZone());
        } catch (\Exception $e) {
            $this->logger->error('Event time zone cannot be resolved, expanding on {fallback}: {timeZone}', [
                'event' => 'event_time_zone_fallback',
                'eventId' => (string) $event->getId(),
                'timeZone' => $event->getTimeZone(),
                'fallback' => Event::FALLBACK_TIME_ZONE,
                'exception' => $e,
            ]);

            return new \DateTimeZone(Event::FALLBACK_TIME_ZONE);
        }
    }

    /**
     * Expand all events (including recurring) within a date range.
     *
     * @param Event[] $events
     *
     * @return Event[]
     */
    public function expandAll(array $events, \DateTimeImmutable $rangeStart, \DateTimeImmutable $rangeEnd): array
    {
        $expanded = [];

        foreach ($events as $event) {
            if ($event->isException()) {
                // Exception instances are handled when expanding the parent
                continue;
            }

            if ($event->isRecurring()) {
                $expanded = array_merge($expanded, $this->expandOccurrences($event, $rangeStart, $rangeEnd));
            } else {
                $expanded[] = $event;
            }
        }

        // Sort by start time
        usort($expanded, fn (Event $a, Event $b) => $a->getStartInstant() <=> $b->getStartInstant());

        return $expanded;
    }
}
