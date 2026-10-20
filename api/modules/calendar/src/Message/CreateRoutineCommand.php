<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** A new routine (MAG-511), from the fields as the client sent them. */
final readonly class CreateRoutineCommand
{
    /** @param array<string, mixed> $fields title, anchor, anchorTime, recurrence, active, remind */
    public function __construct(
        public string $userId,
        public array $fields,
    ) {
    }
}
