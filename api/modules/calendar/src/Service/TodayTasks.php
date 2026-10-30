<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskMoment;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Core\Entity\User;

/**
 * « Aujourd'hui » (MAG-405): the tasks not done that are planned today
 * (MAG-718) or due today or late. The planned ones come first, by moment then
 * time — the plan of the day —, then the others in the order of their score
 * (MAG-406, {@see TaskUrgency::sort()}); each task is followed by its own
 * open subtasks in score order. A task neither planned today nor due is never
 * in; a subtask whose task is not listed keeps a row of its own.
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
        $today = $this->urgency->today($user);
        $due = [];
        foreach ([...$this->taskRepository->findOpenScheduledOn($user, $today), ...$this->taskRepository->findOpenDueUntil($user, $today)] as $task) {
            $due[(string) $task->getId()] ??= $task;
        }
        $due = array_values($due);

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
        $marked = array_map($this->urgency->mark(...), $heads);
        $planned = array_values(array_filter($marked, static fn (Task $task): bool => $task->getScheduledDate()?->format('Y-m-d') === $today->format('Y-m-d')));
        $others = array_values(array_filter($marked, static fn (Task $task): bool => $task->getScheduledDate()?->format('Y-m-d') !== $today->format('Y-m-d')));

        foreach ([...self::byMoment($this->urgency->sort($planned)), ...$this->urgency->sort($others)] as $head) {
            $listed[] = $head;
            foreach ($this->urgency->sort($subtasks[(string) $head->getId()] ?? []) as $subtask) {
                $listed[] = $subtask;
            }
        }

        return $listed;
    }

    /**
     * The tasks of one day by moment, a precise time within the part of the
     * day it falls in, after the tasks planned for that part and by hour; a
     * task with no moment last. Stable: ties keep their order.
     *
     * @param list<Task> $tasks
     *
     * @return list<Task>
     */
    public static function byMoment(array $tasks): array
    {
        $rank = static function (Task $task): array {
            $moment = $task->getScheduledMoment();
            $time = $task->getScheduledTime();
            if (TaskMoment::Time === $moment && null !== $time) {
                return [self::partRank(TaskMoment::partOf($time)), 1, $time];
            }

            return [null === $moment ? 3 : self::partRank($moment), 0, ''];
        };
        $keyed = array_map(static fn (Task $task, int $i): array => [$rank($task), $i, $task], $tasks, array_keys($tasks));
        usort($keyed, static fn (array $a, array $b): int => [$a[0], $a[1]] <=> [$b[0], $b[1]]);

        return array_map(static fn (array $entry): Task => $entry[2], $keyed);
    }

    private static function partRank(TaskMoment $moment): int
    {
        return match ($moment) {
            TaskMoment::Morning => 0,
            TaskMoment::Afternoon => 1,
            TaskMoment::Evening, TaskMoment::Time => 2,
        };
    }
}
