<?php

declare(strict_types=1);

namespace Maggie\Calendar\Event;

/** A done task was ticked off again (MAG-497): the opposite of {@see TaskCompleted}. */
final readonly class TaskReopened
{
    public function __construct(
        public string $taskId,
        public string $userId,
    ) {
    }
}
