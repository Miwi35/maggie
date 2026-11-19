<?php

declare(strict_types=1);

namespace Maggie\Calendar\Event;

/**
 * The day of a recurring task's open occurrence has passed and it is not done
 * (MAG-870): a temporal fact, raised by `maggie:calendar:advance-recurring-tasks`.
 */
final readonly class TaskOccurrencePassed
{
    public function __construct(
        public string $taskId,
        public string $userId,
    ) {
    }
}
