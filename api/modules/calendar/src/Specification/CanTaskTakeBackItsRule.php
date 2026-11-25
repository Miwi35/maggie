<?php

declare(strict_types=1);

namespace Maggie\Calendar\Specification;

use Maggie\Calendar\Entity\Task;

/**
 * A reopened occurrence takes its rule back (MAG-1033): it handed the rule
 * over, and the next occurrence is still open and still carries it — nobody
 * has worked on it nor changed the series since.
 */
final readonly class CanTaskTakeBackItsRule
{
    public function isSatisfiedBy(Task $task): bool
    {
        if ($task->isDone() || null !== $task->getRrule()) {
            return false;
        }
        $next = $task->getNextOccurrence();

        return null !== $next && !$next->isDone() && null !== $next->getRrule();
    }
}
