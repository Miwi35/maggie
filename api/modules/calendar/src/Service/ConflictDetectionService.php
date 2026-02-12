<?php

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Repository\EventRepository;

class ConflictDetectionService
{
    public function __construct(
        private readonly EventRepository $eventRepository,
        private readonly RecurrenceService $recurrenceService,
    ) {
    }

    /**
     * Check for conflicts with a proposed time slot.
     * Returns all events that overlap with the given time range.
     *
     * @return Event[]
     */
    public function findConflicts(
        \DateTimeImmutable $start,
        \DateTimeImmutable $end,
        ?Event $excludeEvent = null,
    ): array {
        // Fetch all events in the range (including recurring masters)
        $events = $this->eventRepository->findByDateRange($start, $end);

        // Expand recurring events
        $expanded = $this->recurrenceService->expandAll($events, $start, $end);

        // Filter to only overlapping events
        $conflicts = [];
        foreach ($expanded as $event) {
            // Skip the event we're checking against (for updates)
            if ($excludeEvent !== null && $event->getId()->equals($excludeEvent->getId())) {
                continue;
            }

            // Skip all-day events — they don't create time conflicts
            if ($event->isAllDay()) {
                continue;
            }

            // Check overlap: event starts before our end AND event ends after our start
            if ($event->getStartAt() < $end && $event->getEndAt() > $start) {
                $conflicts[] = $event;
            }
        }

        return $conflicts;
    }

    /**
     * Check if a proposed time slot has any conflicts.
     */
    public function hasConflicts(
        \DateTimeImmutable $start,
        \DateTimeImmutable $end,
        ?Event $excludeEvent = null,
    ): bool {
        return count($this->findConflicts($start, $end, $excludeEvent)) > 0;
    }
}
