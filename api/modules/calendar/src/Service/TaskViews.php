<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskPriority;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Core\Entity\User;

/**
 * The four views of the tasks (MAG-408), each task in exactly one:
 * `today` ({@see TodayTasks}), `upcoming` (not done, due after today),
 * `someday` (not done, no due day) and `done`. A task already listed in
 * `today` — a subtask that follows its task — is left out of the others.
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

        $upcoming = $this->upcoming($user, $inToday);
        $someday = $this->someday($user, $inToday);
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
     * @param array<string, true> $inToday
     *
     * @return list<Task> by due day, then by score
     */
    private function upcoming(User $user, array $inToday): array
    {
        $tasks = $this->marked($this->taskRepository->findOpenDueAfter($user, $this->urgency->today($user)), $inToday);
        // The score sort leaves ties by due day, so sort by day first, score within a day.
        $byDay = [];
        foreach ($tasks as $task) {
            $byDay[$task->getDueDate()?->format('Y-m-d') ?? ''][] = $task;
        }
        ksort($byDay);

        return array_merge([], ...array_map($this->urgency->sort(...), array_values($byDay)));
    }

    /**
     * @param array<string, true> $inToday
     *
     * @return list<Task> by priority, then by manual order
     */
    private function someday(User $user, array $inToday): array
    {
        $tasks = $this->marked($this->taskRepository->findOpenUndated($user), $inToday);
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
     * @param Task[]              $tasks
     * @param array<string, true> $inToday
     *
     * @return list<Task>
     */
    private function marked(array $tasks, array $inToday): array
    {
        return array_values(array_map(
            $this->urgency->mark(...),
            array_filter($tasks, static fn (Task $task): bool => !isset($inToday[(string) $task->getId()])),
        ));
    }
}
