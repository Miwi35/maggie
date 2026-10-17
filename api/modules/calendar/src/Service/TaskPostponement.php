<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Repository\DayPlanRepository;
use Maggie\Core\Entity\User;
use Symfony\Contracts\Service\ResetInterface;

/**
 * « Reportée » (MAG-497): a task not done is put off since its due day once
 * that day is past, or since the first past settled day it held a slot of (a
 * proposal never taken does not count) — a day is never carried over in
 * silence, what it left undone goes back to the reserve marked so. The
 * earliest of the two. It replaces the red of lateness for an
 * ordinary task; never a reproach.
 */
final class TaskPostponement implements ResetInterface
{
    /** @var array<string, array<string, string>> user id => task id => first past planned day, Y-m-d */
    private array $planned = [];

    public function __construct(
        private readonly DayPlanRepository $dayPlans,
    ) {
    }

    public function since(Task $task, \DateTimeImmutable $today): ?\DateTimeImmutable
    {
        if ($task->isDone()) {
            return null;
        }

        return $this->putOffSince($task, $today);
    }

    /**
     * The day the task was put off since, as if it were still open on $today:
     * how long a task just ticked had waited (MAG-508).
     */
    public function putOffSince(Task $task, \DateTimeImmutable $today): ?\DateTimeImmutable
    {
        $days = [];
        $dueDay = $task->getDueDate()?->format('Y-m-d');
        if (null !== $dueDay && $dueDay < $today->format('Y-m-d')) {
            $days[] = $dueDay;
        }
        $plannedDay = $this->plannedBefore($task->getUser(), $today)[(string) $task->getId()] ?? null;
        if (null !== $plannedDay) {
            $days[] = $plannedDay;
        }

        return [] === $days ? null : new \DateTimeImmutable(min($days).' 00:00:00', new \DateTimeZone('UTC'));
    }

    public function reset(): void
    {
        $this->planned = [];
    }

    /** @return array<string, string> task id => the first day before $today it held a slot */
    private function plannedBefore(User $user, \DateTimeImmutable $today): array
    {
        $key = $user->getId().'@'.$today->format('Y-m-d');
        if (!isset($this->planned[$key])) {
            $first = [];
            foreach ($this->dayPlans->findSettledBefore($user, $today) as $plan) {
                foreach ($plan->getTaskIds() as $taskId) {
                    $first[$taskId] ??= $plan->getDate()->format('Y-m-d');
                }
            }
            $this->planned[$key] = $first;
        }

        return $this->planned[$key];
    }
}
