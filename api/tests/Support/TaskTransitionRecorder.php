<?php

declare(strict_types=1);

namespace App\Tests\Support;

use Maggie\Calendar\Event\TaskCompleted;
use Maggie\Calendar\Event\TaskReopened;

/**
 * Listens to the event bus for the transitions of a task (MAG-497), as the
 * Rewards lot will: the bus is not traced in the test environment, and a
 * spy wired as a real handler proves the events reach their listeners.
 */
final class TaskTransitionRecorder
{
    /** @var list<TaskCompleted|TaskReopened> */
    private array $events = [];

    public function __invoke(TaskCompleted|TaskReopened $event): void
    {
        $this->events[] = $event;
    }

    /** @return list<TaskCompleted|TaskReopened> */
    public function events(): array
    {
        return $this->events;
    }

    public function clear(): void
    {
        $this->events = [];
    }
}
