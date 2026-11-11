<?php

declare(strict_types=1);

namespace Maggie\Health\Event;

/**
 * A planned session's event was moved, resized or deleted in the agenda
 * (MAG-777). A deleted one carries its session's id, read before the flush:
 * the database forgets the link with the event.
 */
final readonly class SportEventChanged
{
    public function __construct(
        public string $userId,
        public string $eventId,
        public bool $removed = false,
        public ?string $workoutId = null,
    ) {
    }
}
