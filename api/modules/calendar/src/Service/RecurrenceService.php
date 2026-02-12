<?php

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\EventStatus;
use Maggie\Calendar\Repository\EventRepository;
use Recurr\Rule;
use Recurr\Transformer\ArrayTransformer;
use Recurr\Transformer\ArrayTransformerConfig;

class RecurrenceService
{
    private ArrayTransformer $transformer;

    public function __construct(
        private readonly EventRepository $eventRepository,
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

        $rruleString = sprintf(
            "DTSTART:%s\nRRULE:%s",
            $event->getStartAt()->format('Ymd\THis\Z'),
            $event->getRrule()
        );

        $rule = new Rule($rruleString);
        $rule->setStartDate($event->getStartAt());

        $recurrences = $this->transformer->transform($rule);

        // Get exceptions for this recurring event
        $exceptions = $this->eventRepository->findExceptionsForRecurringEvent($event);
        $exceptionDates = [];
        foreach ($exceptions as $exception) {
            if ($exception->getOriginalStartAt() !== null) {
                $exceptionDates[$exception->getOriginalStartAt()->format('Y-m-d\TH:i:s')] = $exception;
            }
        }

        $duration = $event->getStartAt()->diff($event->getEndAt());
        $occurrences = [];

        foreach ($recurrences as $recurrence) {
            $occurrenceStart = \DateTimeImmutable::createFromMutable($recurrence->getStart());

            // Skip occurrences outside range
            if ($occurrenceStart >= $rangeEnd) {
                break;
            }
            $occurrenceEnd = $occurrenceStart->add($duration);
            if ($occurrenceEnd <= $rangeStart) {
                continue;
            }

            // Check if this occurrence has an exception
            $key = $occurrenceStart->format('Y-m-d\TH:i:s');
            if (isset($exceptionDates[$key])) {
                $exception = $exceptionDates[$key];
                if ($exception->getStatus() !== EventStatus::Cancelled) {
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
     * Expand all events (including recurring) within a date range.
     *
     * @param Event[] $events
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
        usort($expanded, fn (Event $a, Event $b) => $a->getStartAt() <=> $b->getStartAt());

        return $expanded;
    }
}
