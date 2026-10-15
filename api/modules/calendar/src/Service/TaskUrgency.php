<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskUrgencyLevel;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Core\Entity\User;
use Symfony\Contracts\Service\ResetInterface;

/**
 * Urgency and order (MAG-406), the two tables of the spec « Gestion des
 * tâches ». The urgency comes from the criticality and how close — or how far
 * past — the due day is; it is what the alerts follow. The order score adds
 * the pressure of time, the criticality and the priority, with weights the
 * user can change ({@see TaskOrderWeights}); it only orders what is shown.
 *
 * A done task has no pressure left: it is `low`, and scores by its
 * criticality and priority alone.
 */
final class TaskUrgency implements ResetInterface
{
    /**
     * Rows: criticality. Columns: far (> 3 days), soon (1 to 3 days), today, late.
     *
     * @var array<string, array{TaskUrgencyLevel, TaskUrgencyLevel, TaskUrgencyLevel, TaskUrgencyLevel}>
     */
    private const TABLE = [
        'low' => [TaskUrgencyLevel::Low, TaskUrgencyLevel::Low, TaskUrgencyLevel::Normal, TaskUrgencyLevel::High],
        'medium' => [TaskUrgencyLevel::Low, TaskUrgencyLevel::Normal, TaskUrgencyLevel::High, TaskUrgencyLevel::High],
        'high' => [TaskUrgencyLevel::Normal, TaskUrgencyLevel::High, TaskUrgencyLevel::High, TaskUrgencyLevel::Critical],
        'critical' => [TaskUrgencyLevel::High, TaskUrgencyLevel::Critical, TaskUrgencyLevel::Critical, TaskUrgencyLevel::Critical],
    ];

    /** @var array<string, array<string, array<string, int>>> */
    private array $weights = [];

    public function __construct(
        private readonly TaskOverdue $overdue,
        private readonly TaskSettingsRepository $settingsRepository,
        private readonly TaskPostponement $postponement,
    ) {
    }

    public function urgency(Task $task, \DateTimeImmutable $today): TaskUrgencyLevel
    {
        $days = self::daysUntilDue($task, $today);
        if (null === $days) {
            return TaskUrgencyLevel::Low;
        }

        $column = match (true) {
            $days < 0 => 3,
            0 === $days => 2,
            $days <= 3 => 1,
            default => 0,
        };

        return self::TABLE[$task->getCriticality()->value][$column];
    }

    /** @param array<string, array<string, int>> $weights {@see TaskOrderWeights} */
    public function orderScore(Task $task, \DateTimeImmutable $today, array $weights): int
    {
        $time = $weights['timePressure'];
        $days = self::daysUntilDue($task, $today);

        $pressure = match (true) {
            null === $days => $time['later'],
            $days < 0 => min($time['overdue'] + $time['overduePerDay'] * -$days, max($time['overdue'], $time['overdueMax'])),
            0 === $days => $time['today'],
            1 === $days => $time['tomorrow'],
            $days <= 3 => $time['in2To3Days'],
            $days <= 7 => $time['in4To7Days'],
            $days <= 30 => $time['in8To30Days'],
            default => $time['later'],
        };

        return $pressure
            + $weights['criticality'][$task->getCriticality()->value]
            + $weights['priority'][$task->getPriority()->value];
    }

    /**
     * The order shown: the highest score first, then the closest due day
     * (undated last), then the manual order. The tasks must be marked.
     *
     * @param list<Task> $tasks
     *
     * @return list<Task>
     */
    public function sort(array $tasks): array
    {
        usort($tasks, static fn (Task $a, Task $b): int => [$b->getOrderScore() ?? 0, null === $a->getDueDate(), $a->getDueDate()?->format('Y-m-d'), $a->getPosition() ?? "\u{10FFFF}", (string) $a->getId()]
            <=> [$a->getOrderScore() ?? 0, null === $b->getDueDate(), $b->getDueDate()?->format('Y-m-d'), $b->getPosition() ?? "\u{10FFFF}", (string) $b->getId()]);

        return $tasks;
    }

    /** Today in the user's timezone: {@see TaskOverdue::today()}. */
    public function today(User $user): \DateTimeImmutable
    {
        return $this->overdue->today($user);
    }

    /** Fills the task's `overdueDays`, `urgency`, `orderScore` and `postponedSince` for today, in its owner's timezone and with their weights. */
    public function mark(Task $task): Task
    {
        $this->overdue->mark($task);
        $today = $this->overdue->today($task->getUser());

        return $task
            ->setUrgency($this->urgency($task, $today))
            ->setOrderScore($this->orderScore($task, $today, $this->weightsOf($task->getUser())))
            ->setPostponedSince($this->postponement->since($task, $today));
    }

    public function reset(): void
    {
        $this->weights = [];
    }

    /**
     * The user's weights, the defaults when they never changed one. Read, not
     * created: a read of the tasks writes nothing.
     *
     * @return array<string, array<string, int>>
     */
    private function weightsOf(User $user): array
    {
        $key = (string) $user->getId();

        return $this->weights[$key] ??= $this->settingsRepository->findOneByUser($user)?->getOrderWeights()
            ?? TaskOrderWeights::DEFAULTS;
    }

    /** Days from today to the due day, negative when past; null when undated or done. */
    public static function daysUntilDue(Task $task, \DateTimeImmutable $today): ?int
    {
        $dueDate = $task->getDueDate();
        if ($task->isDone() || null === $dueDate) {
            return null;
        }

        $utc = new \DateTimeZone('UTC');
        $due = new \DateTimeImmutable($dueDate->format('Y-m-d').' 00:00:00', $utc);
        $diff = (new \DateTimeImmutable($today->format('Y-m-d').' 00:00:00', $utc))->diff($due);

        return $diff->invert ? -(int) $diff->days : (int) $diff->days;
    }
}
