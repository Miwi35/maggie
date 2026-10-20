<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** Changes a routine (MAG-511): only the fields present, the others keep their value. */
final readonly class UpdateRoutineCommand
{
    /** @param array<string, mixed> $fields */
    public function __construct(
        public string $userId,
        public string $routineId,
        public array $fields,
    ) {
    }
}
