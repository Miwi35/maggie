<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskAlertReason;
use Maggie\Calendar\Enum\TaskUrgencyLevel;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Core\Entity\User;

/**
 * Which tasks to alert on a morning, and why (MAG-407) — the spec's « Barème
 * d'alertes », a setting of {@see \Maggie\Calendar\Entity\TaskSettings}. Each
 * task follows the schedule of its urgency on that morning
 * ({@see TaskUrgency}): the urgency climbs as the due day nears, so does the
 * schedule. Per urgency:
 *
 * - `daysBefore`: the days before the due day that alert, whole numbers 1 to 30;
 * - `dueDay`: whether the morning of the due day alerts;
 * - `overdueEveryDays`: late, alert every n days (n days late, 2n…), null: never.
 *
 * `low` has no schedule: a low task is only shown, never alerted. A task done
 * or with no due day never alerts. Sending is MAG-410's.
 *
 * @phpstan-type Rule array{daysBefore: list<int>, dueDay: bool, overdueEveryDays: int|null}
 * @phpstan-type Schedule array<string, Rule>
 */
final class TaskAlertSchedule
{
    public const MAX_DAYS = 30;

    public const DEFAULTS = [
        'critical' => ['daysBefore' => [3, 1], 'dueDay' => true, 'overdueEveryDays' => 1],
        'high' => ['daysBefore' => [1], 'dueDay' => true, 'overdueEveryDays' => 1],
        'normal' => ['daysBefore' => [], 'dueDay' => true, 'overdueEveryDays' => 2],
    ];

    public function __construct(
        private readonly TaskRepository $taskRepository,
        private readonly TaskSettingsRepository $settingsRepository,
        private readonly TaskUrgency $urgency,
    ) {
    }

    /**
     * The user's tasks to alert on the morning of $day, by due day then
     * manual order. Read, not created: no settings means the defaults.
     *
     * @return list<TaskAlert>
     */
    public function dueAlerts(User $user, \DateTimeImmutable $day): array
    {
        $day = new \DateTimeImmutable($day->format('Y-m-d').' 00:00:00', new \DateTimeZone('UTC'));
        $schedule = $this->settingsRepository->findOneByUser($user)?->getAlertSchedule() ?? self::DEFAULTS;

        $furthest = max([0, ...array_merge(...array_column($schedule, 'daysBefore'))]);
        $alerts = [];
        foreach ($this->taskRepository->findOpenDueUntil($user, $day->modify("+{$furthest} days")) as $task) {
            $alert = $this->alertFor($task, $day, $schedule);
            if (null !== $alert) {
                $alerts[] = $alert;
            }
        }

        return $alerts;
    }

    /** @param Schedule $schedule */
    public function alertFor(Task $task, \DateTimeImmutable $day, array $schedule): ?TaskAlert
    {
        $days = TaskUrgency::daysUntilDue($task, $day);
        if (null === $days) {
            return null;
        }

        $urgency = $this->urgency->urgency($task, $day);
        $reason = self::reasonFor($schedule[$urgency->value] ?? null, $days);

        return null === $reason ? null : new TaskAlert($task, $reason, abs($days), $urgency);
    }

    /**
     * The reason a rule alerts a task due in $days (negative: late), or null.
     *
     * @param Rule|null $rule
     */
    public static function reasonFor(?array $rule, int $days): ?TaskAlertReason
    {
        if (null === $rule) {
            return null;
        }

        return match (true) {
            $days > 0 => \in_array($days, $rule['daysBefore'], true) ? TaskAlertReason::DaysBefore : null,
            0 === $days => $rule['dueDay'] ? TaskAlertReason::DueDay : null,
            default => null !== $rule['overdueEveryDays'] && 0 === -$days % $rule['overdueEveryDays'] ? TaskAlertReason::Overdue : null,
        };
    }

    /**
     * The schedule stored, completed with the defaults for any urgency or key
     * it lacks, or holds in a shape it no longer accepts.
     *
     * @param array<string, mixed> $stored
     *
     * @return Schedule
     */
    public static function complete(array $stored): array
    {
        $schedule = self::DEFAULTS;
        foreach (self::DEFAULTS as $urgency => $rule) {
            foreach (array_keys($rule) as $key) {
                if (!\is_array($stored[$urgency] ?? null) || !\array_key_exists($key, $stored[$urgency])) {
                    continue;
                }
                try {
                    $schedule[$urgency][$key] = self::value($urgency, $key, $stored[$urgency][$key]);
                } catch (\InvalidArgumentException) {
                }
            }
        }

        return $schedule;
    }

    /**
     * $current with the rules $given changes; the urgencies and keys not sent
     * keep their value.
     *
     * @param array<string, mixed> $current
     *
     * @return Schedule
     *
     * @throws \InvalidArgumentException on an unknown urgency or key, or a value out of its range
     */
    public static function merge(array $current, mixed $given): array
    {
        $known = implode(', ', array_keys(self::DEFAULTS));
        if (!\is_array($given) || ([] !== $given && array_is_list($given))) {
            throw new \InvalidArgumentException("alertSchedule must be an object of urgencies: {$known}.");
        }

        $schedule = self::complete($current);
        foreach ($given as $urgency => $rule) {
            if (!\array_key_exists($urgency, self::DEFAULTS)) {
                $why = TaskUrgencyLevel::Low->value === $urgency ? ' A low task is never alerted.' : '';
                throw new \InvalidArgumentException(\sprintf('Unknown alertSchedule urgency "%s".%s Known urgencies: %s.', $urgency, $why, $known));
            }
            if (!\is_array($rule) || ([] !== $rule && array_is_list($rule))) {
                throw new \InvalidArgumentException(\sprintf('alertSchedule.%s must be an object of daysBefore, dueDay, overdueEveryDays.', $urgency));
            }
            foreach ($rule as $key => $value) {
                if (!\array_key_exists($key, self::DEFAULTS[$urgency])) {
                    throw new \InvalidArgumentException(\sprintf('Unknown key "alertSchedule.%s.%s". Known keys: daysBefore, dueDay, overdueEveryDays.', $urgency, $key));
                }
                $schedule[$urgency][$key] = self::value($urgency, $key, $value);
            }
        }

        return $schedule;
    }

    /** @return list<int>|bool|int|null the value checked, `daysBefore` sorted from the furthest day, without repeats */
    private static function value(string $urgency, string $key, mixed $value): array|bool|int|null
    {
        $isDays = static fn (mixed $n): bool => \is_int($n) && $n >= 1 && $n <= self::MAX_DAYS;

        switch ($key) {
            case 'daysBefore':
                if (!\is_array($value) || !array_is_list($value) || [] !== array_filter($value, static fn (mixed $n): bool => !$isDays($n))) {
                    throw new \InvalidArgumentException(\sprintf('alertSchedule.%s.daysBefore must be a list of whole numbers from 1 to %d.', $urgency, self::MAX_DAYS));
                }
                $days = array_values(array_unique($value));
                rsort($days);

                return $days;
            case 'dueDay':
                if (!\is_bool($value)) {
                    throw new \InvalidArgumentException(\sprintf('alertSchedule.%s.dueDay must be true or false.', $urgency));
                }

                return $value;
            default:
                if (null !== $value && !$isDays($value)) {
                    throw new \InvalidArgumentException(\sprintf('alertSchedule.%s.overdueEveryDays must be null or a whole number from 1 to %d.', $urgency, self::MAX_DAYS));
                }

                return $value;
        }
    }
}
