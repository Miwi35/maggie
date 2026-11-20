<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** Hands the rule of an occurrence that is over to the next one (MAG-870); one that is not over keeps it. */
final readonly class AdvanceTaskRecurrenceCommand
{
    public function __construct(
        public string $userId,
        public string $taskId,
    ) {
    }
}
