<?php

declare(strict_types=1);

namespace Maggie\Calendar\Specification;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Service\TaskRecurrence;

/**
 * The occurrence of a recurring task is over (MAG-870): done, or its day has
 * passed without it — the rule then goes to the next one.
 */
final readonly class IsTaskOccurrenceOver
{
    private \DateTimeImmutable $today;

    /** @param \DateTimeImmutable $today the owner's today, a day ({@see \Maggie\Calendar\Service\TaskOverdue::today()}) */
    public function __construct(\DateTimeImmutable $today)
    {
        $this->today = new \DateTimeImmutable($today->format('Y-m-d'), new \DateTimeZone('UTC'));
    }

    public function isSatisfiedBy(Task $task): bool
    {
        if (null === $task->getRrule()) {
            return false;
        }
        if ($task->isDone()) {
            return true;
        }
        $day = TaskRecurrence::anchor($task);

        return null !== $day && $day < $this->today;
    }
}
