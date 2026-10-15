<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\DayPlan;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskCriticality;
use Maggie\Calendar\Enum\TaskUrgencyLevel;

/**
 * The rule of the morning (MAG-498), pure: given the slots already written,
 * the open tasks and the free time, which five to propose. No clock, no
 * database, no model — the judgement of Maggie comes later on the same shape.
 *
 * Candidates are ranked by tier: put off, due today, urgent (urgency critical
 * or high, or a critical task), due tomorrow, the rest — each tier by the
 * order score. The day is filled up to five while the estimated minutes fit
 * the free time, at least one task; the ones kept come out shortest first.
 */
final class DayProposalRule
{
    public const DEFAULT_MINUTES = 15;

    public const POSTPONED = 'postponed';
    public const DUE_TODAY = 'dueToday';
    public const URGENT = 'urgent';
    public const DUE_TOMORROW = 'dueTomorrow';
    public const QUICK = 'quick';
    public const WHENEVER = 'whenever';

    /** A task of this length or less, with no pressure, is « rapide ». */
    private const QUICK_MINUTES = 15;

    public static function minutes(Task $task): int
    {
        return $task->getEstimatedMinutes() ?? self::DEFAULT_MINUTES;
    }

    /**
     * Why a task comes before the others, in the order of the tiers. The task
     * must be marked ({@see TaskUrgency::mark()}).
     */
    public static function reason(Task $task, \DateTimeImmutable $today): string
    {
        return match (self::tier($task, $today)) {
            0 => self::POSTPONED,
            1 => self::DUE_TODAY,
            2 => self::URGENT,
            3 => self::DUE_TOMORROW,
            default => self::minutes($task) <= self::QUICK_MINUTES ? self::QUICK : self::WHENEVER,
        };
    }

    /**
     * @param list<Task> $candidates open tasks, marked, not subtasks, none of them in $kept
     *
     * @return list<Task> best first, the order of the reserve once the five are taken
     */
    public static function rank(array $candidates, \DateTimeImmutable $today): array
    {
        $byTier = [];
        foreach ($candidates as $task) {
            $byTier[self::tier($task, $today)][] = $task;
        }
        ksort($byTier);

        $ranked = [];
        foreach ($byTier as $tasks) {
            usort($tasks, static fn (Task $a, Task $b): int => [$b->getOrderScore() ?? 0, null === $a->getDueDate(), $a->getDueDate()?->format('Y-m-d'), $a->getPosition() ?? "\u{10FFFF}", (string) $a->getId()]
                <=> [$a->getOrderScore() ?? 0, null === $b->getDueDate(), $b->getDueDate()?->format('Y-m-d'), $b->getPosition() ?? "\u{10FFFF}", (string) $b->getId()]);
            array_push($ranked, ...$tasks);
        }

        return $ranked;
    }

    /**
     * @param list<Task> $kept       the slots already written for the day, in order: kept, and counted in the five
     * @param list<Task> $candidates see {@see rank()}
     *
     * @return array{slots: list<Task>, reserve: list<Task>}
     */
    public static function propose(array $kept, array $candidates, \DateTimeImmutable $today, int $freeMinutes): array
    {
        $ranked = self::rank($candidates, $today);

        $chosen = $kept;
        // A done task takes a slot but no time any more.
        $total = array_sum(array_map(static fn (Task $task): int => $task->isDone() ? 0 : self::minutes($task), $kept));

        while ([] !== $ranked && \count($chosen) < DayPlan::MAX_SLOTS) {
            $minutes = self::minutes($ranked[0]);
            if ([] !== $chosen && $total + $minutes > $freeMinutes) {
                break;
            }
            $total += $minutes;
            $chosen[] = array_shift($ranked);
        }

        // Stable: ties keep the rank order, the written slots first.
        $indexed = array_map(null, $chosen, array_keys($chosen));
        usort($indexed, static fn (array $a, array $b): int => [self::minutes($a[0]), $a[1]] <=> [self::minutes($b[0]), $b[1]]);

        return ['slots' => array_column($indexed, 0), 'reserve' => $ranked];
    }

    private static function tier(Task $task, \DateTimeImmutable $today): int
    {
        if (null !== $task->getPostponedSince()) {
            return 0;
        }

        $days = TaskUrgency::daysUntilDue($task, $today);
        if (0 === $days) {
            return 1;
        }
        if (TaskCriticality::Critical === $task->getCriticality()
            || \in_array($task->getUrgency(), [TaskUrgencyLevel::Critical, TaskUrgencyLevel::High], true)) {
            return 2;
        }

        return 1 === $days ? 3 : 4;
    }
}
