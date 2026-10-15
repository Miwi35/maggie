<?php

declare(strict_types=1);

namespace Maggie\Calendar\Event;

/**
 * A task went from not done to done (MAG-497), whichever door did it: a
 * screen, Maggie, a Google sync. Read by the Rewards lot.
 */
final readonly class TaskCompleted
{
    public function __construct(
        public string $taskId,
        public string $userId,
        public \DateTimeImmutable $completedAt,
    ) {
    }
}
