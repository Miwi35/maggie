<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskPriority;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Core\Entity\User;

/**
 * The four views of the tasks (MAG-408), each task in exactly one:
 * `today` ({@see TodayTasks}), `upcoming` (not done, due or planned after
 * today — MAG-718), `someday` (not done, no due day nor day planned ahead)
 * and `done`. A subtask follows its task, right
 * after it in the task's view whatever its own day, and is in no other.
 */
final class TaskViews
{
    public const TODAY = 'today';
    public const UPCOMING = 'upcoming';
    public const SOMEDAY = 'someday';
    public const DONE = 'done';
    public const ALL = [self::TODAY, self::UPCOMING, self::SOMEDAY, self::DONE];

    public function __construct(
        private readonly TaskRepository $taskRepository,
        private readonly TaskUrgency $urgency,
        private readonly TodayTasks $todayTasks,
    ) {
    }

    /**
     * `tasks` is the page asked for (only `done` pages), `total` the size of
     * the whole view.
     *
     * @return array{tasks: list<Task>, total: int, counts: array{today: int, upcoming: int, someday: int, done: int}}
     */
    public function read(User $user, string $view, int $page = 1, int $itemsPerPage = 30): array
    {
        $today = $this->todayTasks->for($user);
        $inToday = [];
        foreach ($today as $task) {
            $inToday[(string) $task->getId()] = true;
        }

        $todayDay = $this->urgency->today($user);
        $ahead = [];
        foreach ([...$this->taskRepository->findOpenDueAfter($user, $todayDay), ...$this->taskRepository->findOpenScheduledAfter($user, $todayDay)] as $task) {
            $ahead[(string) $task->getId()] ??= $task;
        }
        $dueAfter = array_values($ahead);
        $undated = array_values(array_filter(
            $this->taskRepository->findOpenUndated($user),
            static fn (Task $task): bool => !isset($ahead[(string) $task->getId()]),
        ));

        $followed = [];
        foreach ([...$dueAfter, ...$undated] as $task) {
            $followed[(string) $task->getId()] = true;
        }
        $heads = static fn (array $tasks): array => array_values(array_filter(
            $tasks,
            static fn (Task $task): bool => !isset($inToday[(string) $task->getId()])
                && (null === $task->getParent() || !isset($followed[(string) $task->getParent()->getId()])),
        ));
        $upcomingHeads = $heads($dueAfter);
        $somedayHeads = $heads($undated);

        $subtasks = [];
        foreach ($this->taskRepository->findOpenSubtasksOf($user, [...$upcomingHeads, ...$somedayHeads]) as $subtask) {
            $subtasks[(string) $subtask->getParent()?->getId()][] = $this->urgency->mark($subtask);
        }

        $upcoming = $this->withSubtasks($this->upcoming($upcomingHeads), $subtasks);
        $someday = $this->withSubtasks($this->someday($somedayHeads), $subtasks);
        $doneCount = $this->taskRepository->countDone($user);

        $counts = [
            self::TODAY => \count($today),
            self::UPCOMING => \count($upcoming),
            self::SOMEDAY => \count($someday),
            self::DONE => $doneCount,
        ];

        $tasks = match ($view) {
            self::TODAY => $today,
            self::UPCOMING => $upcoming,
            self::SOMEDAY => $someday,
            self::DONE => array_values(array_map($this->urgency->mark(...), $this->taskRepository->findDonePage($user, $page, $itemsPerPage))),
            default => throw new \InvalidArgumentException(\sprintf('Unknown task view "%s".', $view)),
        };

        return ['tasks' => $tasks, 'total' => $counts[$view], 'counts' => $counts];
    }

    /**
     * @param list<Task> $heads
     *
     * @return list<Task> by day — the day planned, else the due day —, then by score
     */
    private function upcoming(array $heads): array
    {
        $tasks = array_map($this->urgency->mark(...), $heads);
        // The score sort leaves ties by due day, so sort by day first, score within a day.
        $byDay = [];
        foreach ($tasks as $task) {
            $byDay[($task->getScheduledDate() ?? $task->getDueDate())?->format('Y-m-d') ?? ''][] = $task;
        }
        ksort($byDay);

        return array_merge([], ...array_map($this->urgency->sort(...), array_values($byDay)));
    }

    /**
     * @param list<Task> $heads
     *
     * @return list<Task> by priority, then by manual order
     */
    private function someday(array $heads): array
    {
        $tasks = array_map($this->urgency->mark(...), $heads);
        $rank = static fn (Task $task): int => match ($task->getPriority()) {
            TaskPriority::High => 0,
            TaskPriority::Medium => 1,
            TaskPriority::Low => 2,
        };
        usort($tasks, static fn (Task $a, Task $b): int => [$rank($a), $a->getPosition() ?? "\u{10FFFF}", (string) $a->getId()]
            <=> [$rank($b), $b->getPosition() ?? "\u{10FFFF}", (string) $b->getId()]);

        return $tasks;
    }

    /**
     * @param list<Task>                $heads
     * @param array<string, list<Task>> $subtasks by task id
     *
     * @return list<Task> each task followed by its open subtasks, in score order
     */
    private function withSubtasks(array $heads, array $subtasks): array
    {
        $listed = [];
        foreach ($heads as $head) {
            $listed[] = $head;
            foreach ($this->urgency->sort($subtasks[(string) $head->getId()] ?? []) as $subtask) {
                $listed[] = $subtask;
            }
        }

        return $listed;
    }
}
