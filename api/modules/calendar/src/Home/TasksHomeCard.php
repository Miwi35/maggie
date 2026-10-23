<?php

declare(strict_types=1);

namespace Maggie\Calendar\Home;

use Maggie\Calendar\Entity\Routine;
use Maggie\Calendar\Entity\RoutineCheck;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Service\RoutineSchedule;
use Maggie\Calendar\Service\TaskOverdue;
use Maggie\Calendar\Service\TaskUrgency;
use Maggie\Calendar\Service\TodayTasks;
use Maggie\Core\Entity\User;
use Maggie\Core\Home\HomeCardProviderInterface;
use Maggie\Core\Home\HomeCardWatcherInterface;
use Maggie\Core\Home\HomeDay;
use Symfony\Component\Clock\ClockInterface;

/**
 * The Tâches card of the Accueil (MAG-561): the day's tasks and the routines of
 * the moment, in one read. Ticking goes through the existing endpoints (task
 * done, routine check); this only reads.
 *
 * Today: up to {@see self::SLOTS} rows — what was ticked today, then what is
 * left in the order of « Aujourd'hui » — so the ring reads « 3 sur 5 » and a
 * ticked row stays, struck, until midnight. Tomorrow: what is due then,
 * nothing tickable.
 */
final class TasksHomeCard implements HomeCardProviderInterface, HomeCardWatcherInterface
{
    public const int SLOTS = 5;

    public function __construct(
        private readonly TodayTasks $todayTasks,
        private readonly TaskRepository $taskRepository,
        private readonly TaskUrgency $urgency,
        private readonly TaskOverdue $overdue,
        private readonly RoutineSchedule $routines,
        private readonly ClockInterface $clock,
    ) {
    }

    public function module(): string
    {
        return 'tasks';
    }

    public function watchedEntities(): array
    {
        return [Task::class, Routine::class, RoutineCheck::class];
    }

    public function state(User $user, HomeDay $day): array
    {
        $today = HomeDay::Today === $day;
        $open = $today
            ? $this->todayTasks->for($user)
            : $this->urgency->sort(array_map($this->urgency->mark(...), $this->taskRepository->findOpenDueOn($user, $day->date($this->clock))));
        $doneRows = $today ? \array_slice($this->doneToday($user), 0, self::SLOTS) : [];
        $openRows = \array_slice($open, 0, self::SLOTS - \count($doneRows));

        return [
            'editable' => $today,
            'tasks' => [
                ...array_map(static fn (Task $task): array => self::row($task, false), $openRows),
                ...array_map(static fn (Task $task): array => self::row($task, true), $doneRows),
            ],
            'done' => \count($doneRows),
            'total' => \count($doneRows) + \count($openRows),
            'more' => \count($open) - \count($openRows),
            'overdue' => \count(array_filter($open, static fn (Task $task): bool => ($task->getOverdueDays() ?? 0) > 0)),
            'routines' => $this->routinesOf($user, $day),
            // The Sagas lot fills it: {title, done, total}.
            'saga' => null,
        ];
    }

    /** @return list<Task> */
    private function doneToday(User $user): array
    {
        $start = new \DateTimeImmutable($this->overdue->today($user)->format('Y-m-d'), $this->overdue->zone($user));

        return $this->taskRepository->findCompletedBetween($user, $start, $start->modify('+1 day'));
    }

    /** @return array{id: string, title: string, done: bool, overdueDays: int|null, parentId: string|null} */
    private static function row(Task $task, bool $done): array
    {
        return [
            'id' => (string) $task->getId(),
            'title' => $task->getTitle(),
            'done' => $done,
            'overdueDays' => $done ? null : $task->getOverdueDays(),
            'parentId' => null !== $task->getParent() ? (string) $task->getParent()->getId() : null,
        ];
    }

    /**
     * Today: the routines due now, of the current moment of the day. Tomorrow:
     * every routine due then.
     *
     * @return list<array{id: string, title: string, anchor: string, time: string, checked: bool}>
     */
    private function routinesOf(User $user, HomeDay $day): array
    {
        $groups = $this->routines->day($user, $day->date($this->clock))['moments'];
        if (HomeDay::Today === $day) {
            $anchor = $this->currentAnchor($user);
            $groups = array_values(array_filter($groups, static fn (array $group): bool => $group['anchor'] === $anchor));
        }

        $rows = [];
        foreach ($groups as $group) {
            foreach ($group['routines'] as $routine) {
                $rows[] = [
                    'id' => $routine['id'],
                    'title' => $routine['title'],
                    'anchor' => $group['anchor'],
                    'time' => $group['time'],
                    'checked' => $routine['checked'],
                ];
            }
        }

        return $rows;
    }

    /** The latest moment of the day already started, the first one before the day's first. */
    private function currentAnchor(User $user): string
    {
        $moments = $this->routines->moments($user);
        asort($moments);
        $now = $this->clock->now()->setTimezone($this->overdue->zone($user))->format('H:i');

        $current = array_key_first($moments);
        foreach ($moments as $anchor => $time) {
            if ($time <= $now) {
                $current = $anchor;
            }
        }

        return (string) $current;
    }
}
