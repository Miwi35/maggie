<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** Ticks a routine on a local date, or takes the tick back (MAG-511). Both idempotent. */
final readonly class CheckRoutineCommand
{
    public function __construct(
        public string $userId,
        public string $routineId,
        public \DateTimeImmutable $date,
        public bool $checked = true,
    ) {
    }
}
