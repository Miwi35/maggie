<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** Gives the rule back to a reopened occurrence and deletes the next one (MAG-1033), when nothing happened to it. */
final readonly class RewindTaskRecurrenceCommand
{
    public function __construct(
        public string $userId,
        public string $taskId,
    ) {
    }
}
