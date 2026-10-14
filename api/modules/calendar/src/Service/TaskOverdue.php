<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Task;
use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserPreferenceRepository;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Contracts\Service\ResetInterface;

/**
 * Lateness, as the owner reads it (MAG-405): a task not done whose due day has
 * passed is late by the days between that day and today — today being the day
 * of the user's own timezone, so the switch happens at their midnight, not the
 * server's.
 */
final class TaskOverdue implements ResetInterface
{
    private const DEFAULT_TIMEZONE = 'Europe/Paris';

    /** @var array<string, \DateTimeZone> */
    private array $zones = [];

    public function __construct(
        private readonly UserPreferenceRepository $preferenceRepository,
        private readonly ClockInterface $clock,
    ) {
    }

    /** Today in the user's timezone, as a day (midnight UTC, a date carries no zone). */
    public function today(User $user): \DateTimeImmutable
    {
        $local = $this->clock->now()->setTimezone($this->zoneOf($user));

        return self::day($local->format('Y-m-d'));
    }

    /** Whole days late, or null when the task is done, undated, or not yet due. */
    public function daysLate(Task $task, \DateTimeImmutable $today): ?int
    {
        $dueDate = $task->getDueDate();
        if ($task->isDone() || null === $dueDate) {
            return null;
        }

        $due = self::day($dueDate->format('Y-m-d'));
        if ($due >= $today) {
            return null;
        }

        return (int) $due->diff($today)->days;
    }

    /** Fills the task's `overdueDays` for today, in its owner's timezone. */
    public function mark(Task $task): Task
    {
        return $task->setOverdueDays($this->daysLate($task, $this->today($task->getUser())));
    }

    public function reset(): void
    {
        $this->zones = [];
    }

    private function zoneOf(User $user): \DateTimeZone
    {
        $key = (string) $user->getId();
        if (!isset($this->zones[$key])) {
            $name = $this->preferenceRepository->findOneByUser($user)?->getTimezone() ?? self::DEFAULT_TIMEZONE;
            try {
                $this->zones[$key] = new \DateTimeZone($name);
            } catch (\Exception) {
                $this->zones[$key] = new \DateTimeZone(self::DEFAULT_TIMEZONE);
            }
        }

        return $this->zones[$key];
    }

    private static function day(string $day): \DateTimeImmutable
    {
        return new \DateTimeImmutable($day.' 00:00:00', new \DateTimeZone('UTC'));
    }
}
