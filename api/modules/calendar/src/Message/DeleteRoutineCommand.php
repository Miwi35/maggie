<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** Deletes a routine and its ticks (MAG-511). */
final readonly class DeleteRoutineCommand
{
    public function __construct(
        public string $userId,
        public string $routineId,
    ) {
    }
}
