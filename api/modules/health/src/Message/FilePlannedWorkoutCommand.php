<?php

declare(strict_types=1);

namespace Maggie\Health\Message;

/**
 * Brings a session's event in the « Santé » agenda in line with it (MAG-777):
 * created, moved or removed. `eventId` names the event of a session that is
 * gone, which no longer tells it.
 */
final readonly class FilePlannedWorkoutCommand
{
    public function __construct(
        public string $userId,
        public string $workoutId,
        public ?string $eventId = null,
    ) {
    }
}
