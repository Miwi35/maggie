<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Exception\InvalidTripException;
use Maggie\Calendar\Repository\EventRepository;

/**
 * The occurrence a trip serves (MAG-862): a trip is planned on one day of a series, never on its
 * first start. The day is the Paris day of the occurrence's start; a single event is its own occurrence.
 */
class TripOccurrence
{
    public function __construct(
        private readonly RecurrenceService $recurrence,
        private readonly EventRepository $events,
    ) {
    }

    /**
     * The occurrence of `$event` on `$date` (YYYY-MM-DD, Paris): a virtual one carries the series'
     * id, a modified one (an exception) is the stored event. A series needs the day, never guessed.
     *
     * @throws InvalidTripException
     */
    public function on(Event $event, ?string $date): Event
    {
        if (null === $date) {
            if ($event->isRecurring()) {
                throw new InvalidTripException('date', sprintf('« %s » repeats: send the date (YYYY-MM-DD) of the occurrence the trip is for.', $event->getSummary()));
            }

            return $event;
        }

        try {
            [$dayStart, $dayEnd] = TripDay::bounds($date);
        } catch (\InvalidArgumentException $e) {
            throw new InvalidTripException('date', $e->getMessage());
        }

        foreach ($this->recurrence->expandOccurrences($event, $dayStart, $dayEnd) as $occurrence) {
            $start = self::startOf($occurrence);
            if ($start >= $dayStart && $start < $dayEnd) {
                return $occurrence;
            }
        }

        throw new InvalidTripException('date', sprintf('« %s » does not take place on %s.', $event->getSummary(), $date));
    }

    /**
     * The occurrence a kept trip serves, as the series reads today — a single event as it is now.
     * Moved to another day on its own row, it is that exception, known by its original start.
     *
     * @throws InvalidTripException the series no longer takes place that day
     */
    public function of(Trip $trip): Event
    {
        $event = $trip->getEvent();
        if (!$event->isRecurring()) {
            return $event;
        }

        $day = $trip->getOccurrenceStartAt()->setTimezone(new \DateTimeZone(TripDay::TIME_ZONE))->format('Y-m-d');
        try {
            return $this->on($event, $day);
        } catch (InvalidTripException $notThatDay) {
            foreach ($this->events->findExceptionsForRecurringEvent($event) as $exception) {
                $original = $exception->getOriginalStartAt();
                // An all-day exception is known by the midnight UTC of its day (RecurrenceService).
                $sameOccurrence = null !== $event->getStartDate()
                    ? $original?->setTimezone(new \DateTimeZone('UTC'))->format('Y-m-d') === $day
                    : $original?->getTimestamp() === $trip->getOccurrenceStartAt()->getTimestamp();
                if ($sameOccurrence && EventStatus::Cancelled !== $exception->getStatus()) {
                    return $exception;
                }
            }

            throw $notThatDay;
        }
    }

    /** The event a trip to `$occurrence` belongs to: the series for a virtual occurrence, else the occurrence itself. */
    public static function owner(Event $event, Event $occurrence): Event
    {
        return $occurrence->getId()->equals($event->getId()) ? $event : $occurrence;
    }

    /** When the occurrence starts, in Paris: midnight of its first day for a whole-day one. */
    public static function startOf(Event $occurrence): \DateTimeImmutable
    {
        $paris = new \DateTimeZone(TripDay::TIME_ZONE);
        $start = $occurrence->getStartAt();
        if (null !== $start) {
            return $start->setTimezone($paris);
        }

        return new \DateTimeImmutable((string) $occurrence->getStartDate()?->format('Y-m-d'), $paris);
    }
}
