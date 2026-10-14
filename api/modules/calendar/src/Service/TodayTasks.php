<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Core\Entity\User;

/**
 * « Aujourd'hui » (MAG-405): the tasks not done that are due today or late,
 * in the order of their score (MAG-406, {@see TaskUrgency::sort()}), each
 * task followed by its own open subtasks in the same order. A task with no
 * due day is never in; a late subtask whose task is not listed keeps a row of
 * its own.
 */
final class TodayTasks
{
    public function __construct(
        private readonly TaskRepository $taskRepository,
        private readonly TaskUrgency $urgency,
    ) {
    }

    /** @return list<Task> each with its `overdueDays`, `urgency` and `orderScore` filled */
    public function for(User $user): array
    {
        $due = $this->taskRepository->findOpenDueUntil($user, $this->urgency->today($user));

        $dueIds = [];
        foreach ($due as $task) {
            $dueIds[(string) $task->getId()] = true;
        }

        $heads = array_values(array_filter(
            $due,
            static fn (Task $task): bool => null === $task->getParent() || !isset($dueIds[(string) $task->getParent()->getId()]),
        ));

        $subtasks = [];
        foreach ($this->taskRepository->findOpenSubtasksOf($user, $heads) as $subtask) {
            $subtasks[(string) $subtask->getParent()?->getId()][] = $this->urgency->mark($subtask);
        }

        $listed = [];
        foreach ($this->urgency->sort(array_map($this->urgency->mark(...), $heads)) as $head) {
            $listed[] = $head;
            foreach ($this->urgency->sort($subtasks[(string) $head->getId()] ?? []) as $subtask) {
                $listed[] = $subtask;
            }
        }

        return $listed;
    }
}
