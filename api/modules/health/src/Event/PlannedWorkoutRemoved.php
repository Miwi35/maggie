<?php

declare(strict_types=1);

namespace Maggie\Health\Event;

/**
 * A session left the agenda (MAG-777): deleted, or no longer planned. Carries
 * its event's id, read before the flush: a deleted row no longer tells it.
 */
final readonly class PlannedWorkoutRemoved
{
    public function __construct(
        public string $userId,
        public string $workoutId,
        public string $eventId,
    ) {
    }
}
