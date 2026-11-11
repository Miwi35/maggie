<?php

declare(strict_types=1);

namespace Maggie\Health\Message;

/**
 * Brings a planned session in line with its event, moved or deleted in the
 * agenda (MAG-777). `workoutId` names the session of a deleted event.
 */
final readonly class ApplySportEventChangeCommand
{
    public function __construct(
        public string $userId,
        public string $eventId,
        public bool $removed = false,
        public ?string $workoutId = null,
    ) {
    }
}
