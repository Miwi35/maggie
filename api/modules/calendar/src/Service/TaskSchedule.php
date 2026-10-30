<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskMoment;
use Maggie\Calendar\Exception\InvalidTaskScheduleException;

/**
 * The programmation of a task (MAG-718): the day it is planned, the moment of
 * that day, the time and the reminders when the moment is `time`. Settled
 * here for every door — the HTTP processors and Maggie's tools go through the
 * same handlers.
 *
 * What a change leaves out keeps its stored value, unless it no longer holds:
 * a moment other than `time` drops the time and the reminders, no day drops
 * everything. What a change says that does not hold is refused.
 */
final class TaskSchedule
{
    public const TIME_PATTERN = '/^([01]\d|2[0-3]):[0-5]\d$/';

    /** A week before, the furthest a task reminder goes. */
    public const MAX_REMINDER_MINUTES = 10080;

    /** At the time: what a precise time gets when it is given no reminder. */
    public const DEFAULT_REMINDERS = [0];

    /**
     * Applies a change of programmation to $task. Null arguments are "not
     * given"; `$clearDay` empties the whole programmation, `$clearMoment`
     * keeps the day only.
     *
     * @param list<int>|null $reminders minutes before the time; [] for none
     *
     * @throws InvalidTaskScheduleException
     */
    public static function apply(
        Task $task,
        ?\DateTimeImmutable $day = null,
        ?string $moment = null,
        ?string $time = null,
        ?array $reminders = null,
        bool $clearDay = false,
        bool $clearMoment = false,
    ): void {
        $givenMoment = null === $moment ? null : (TaskMoment::tryFrom($moment)
            ?? throw new InvalidTaskScheduleException(\sprintf('scheduledMoment must be one of morning, afternoon, evening, time, not "%s".', $moment)));

        if (null !== $time && 1 !== preg_match(self::TIME_PATTERN, $time)) {
            throw new InvalidTaskScheduleException(\sprintf('scheduledTime must be a time "HH:MM" from 00:00 to 23:59, not "%s".', $time));
        }

        $nextDay = $day ?? ($clearDay ? null : $task->getScheduledDate());
        $nextMoment = $givenMoment ?? ($clearDay || $clearMoment ? null : $task->getScheduledMoment());

        if (null === $nextDay) {
            if (null !== $givenMoment || null !== $time || [] !== ($reminders ?? [])) {
                throw new InvalidTaskScheduleException('A moment, a time or reminders need a scheduledDate.');
            }
            $task->schedule(null, null, null, []);

            return;
        }

        if (TaskMoment::Time !== $nextMoment) {
            if (null !== $time) {
                throw new InvalidTaskScheduleException('scheduledTime is only for scheduledMoment "time".');
            }
            if ([] !== ($reminders ?? [])) {
                throw new InvalidTaskScheduleException('Reminders are only for scheduledMoment "time": a task is reminded at a precise time only.');
            }
            $task->schedule($nextDay, $nextMoment, null, []);

            return;
        }

        $wasTime = TaskMoment::Time === $task->getScheduledMoment();
        $nextTime = $time ?? ($wasTime ? $task->getScheduledTime() : null)
            ?? throw new InvalidTaskScheduleException('scheduledMoment "time" needs a scheduledTime "HH:MM".');
        $nextReminders = null !== $reminders ? self::reminders($reminders) : ($wasTime ? $task->getReminders() : self::DEFAULT_REMINDERS);

        $task->schedule($nextDay, TaskMoment::Time, $nextTime, $nextReminders);
    }

    /**
     * @param list<mixed> $minutes
     *
     * @return list<int> without repeats, the furthest last
     *
     * @throws InvalidTaskScheduleException
     */
    private static function reminders(array $minutes): array
    {
        foreach ($minutes as $delay) {
            if (!\is_int($delay) || $delay < 0 || $delay > self::MAX_REMINDER_MINUTES) {
                throw new InvalidTaskScheduleException(\sprintf('A reminder is a whole number of minutes from 0 (at the time) to %d before it.', self::MAX_REMINDER_MINUTES));
            }
        }
        $minutes = array_values(array_unique($minutes));
        if (\count($minutes) > Event::MAX_REMINDERS) {
            throw new InvalidTaskScheduleException(\sprintf('A task takes at most %d reminders.', Event::MAX_REMINDERS));
        }
        sort($minutes);

        return $minutes;
    }

    /**
     * When the task starts, in $zone: its day at its time. Null when it has no
     * precise time.
     */
    public static function startsAt(Task $task, \DateTimeZone $zone): ?\DateTimeImmutable
    {
        $day = $task->getScheduledDate();
        $time = $task->getScheduledTime();
        if (null === $day || null === $time || TaskMoment::Time !== $task->getScheduledMoment()) {
            return null;
        }

        return new \DateTimeImmutable($day->format('Y-m-d').' '.$time, $zone);
    }
}
