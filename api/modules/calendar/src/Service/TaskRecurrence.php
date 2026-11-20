<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Exception\InvalidTaskScheduleException;
use Recurr\Frequency;
use Recurr\Rule;
use Recurr\Transformer\ArrayTransformer;
use Recurr\Transformer\ArrayTransformerConfig;

/**
 * The recurrence of a task (MAG-870): a rule RFC 5545 carried by the series'
 * only open occurrence, anchored on its day — the scheduled day, else the due
 * day. Settled here for every door, as {@see TaskSchedule} is.
 *
 * The rule is unfolded by days, in UTC, from the day of the rule the
 * occurrence stands for (`recurrenceDate`): an occurrence moved to another
 * day leaves the series on its own days, and `INTERVAL=2` keeps its phase.
 */
final class TaskRecurrence
{
    /** The column's length. */
    public const MAX_LENGTH = 255;

    /** A task is counted in days: nothing finer than a day. */
    private const FREQUENCIES = [Frequency::DAILY, Frequency::WEEKLY, Frequency::MONTHLY, Frequency::YEARLY];

    /**
     * Applies a change of recurrence to $task, after its dates are settled.
     * A null $rrule is "not given": the stored rule is checked again against
     * the dates, which may have changed. A new rule is anchored on the task's
     * day; a moved day alone keeps the day of the rule.
     *
     * @throws InvalidTaskScheduleException
     */
    public static function apply(Task $task, ?string $rrule = null, bool $clear = false): void
    {
        if ($clear && null === $rrule) {
            $task->repeat(null, null);

            return;
        }

        $nextRule = $rrule ?? $task->getRrule();
        if (null === $nextRule) {
            return;
        }

        self::parse($nextRule);
        if (null !== $rrule && $rrule !== $task->getRrule() && $task->isDone()) {
            throw new InvalidTaskScheduleException('A done task cannot take a rrule: only the open occurrence carries the series. Reopen it, or give the rule to an open task.');
        }
        if (null !== $task->getParent()) {
            throw new InvalidTaskScheduleException(\sprintf('A subtask cannot repeat: this task is a subtask of « %s ». Give the rrule to a task of its own.', $task->getParent()->getTitle()));
        }
        $anchor = self::anchor($task)
            ?? throw new InvalidTaskScheduleException('rrule needs the day of the first occurrence: give a scheduledDate or a dueDate on a day of the rule. This task has neither.');

        $recurrenceDate = $task->getRecurrenceDate();
        if (null === $recurrenceDate || $nextRule !== $task->getRrule()) {
            $first = self::days($nextRule, $anchor)[0] ?? throw new InvalidTaskScheduleException(\sprintf('rrule %s has no day from %s on: its UNTIL is already past.', $nextRule, $anchor->format('Y-m-d')));
            if ($first != $anchor) {
                throw new InvalidTaskScheduleException(\sprintf('%s (%s) is not a day of rrule %s: its first day from then is %s (%s). Put the task on a day of the rule.', $anchor->format('Y-m-d'), $anchor->format('l'), $nextRule, $first->format('Y-m-d'), $first->format('l')));
            }
            $recurrenceDate = $anchor;
        }

        $task->repeat($nextRule, $recurrenceDate);
    }

    /**
     * The day of the next occurrence: the first day of the rule after the one
     * $task stands for, from $today on. Null when the rule has no such day
     * (its UNTIL is past) or the task does not repeat.
     */
    public static function nextDay(Task $task, \DateTimeImmutable $today): ?\DateTimeImmutable
    {
        $rrule = $task->getRrule();
        $from = $task->getRecurrenceDate() ?? self::anchor($task);
        if (null === $rrule || null === $from) {
            return null;
        }

        $from = self::day($from);
        $today = self::day($today);
        foreach (self::days($rrule, $from) as $day) {
            if ($day > $from && $day >= $today) {
                return $day;
            }
        }

        return null;
    }

    /** The day the task's rule is anchored on: its scheduled day, else its due day. */
    public static function anchor(Task $task): ?\DateTimeImmutable
    {
        $day = $task->getScheduledDate() ?? $task->getDueDate();

        return null === $day ? null : self::day($day);
    }

    /** @throws InvalidTaskScheduleException */
    private static function parse(string $rrule): void
    {
        if (\strlen($rrule) > self::MAX_LENGTH) {
            throw new InvalidTaskScheduleException(\sprintf('rrule is at most %d characters, not %d.', self::MAX_LENGTH, \strlen($rrule)));
        }
        if (str_starts_with(strtoupper(ltrim($rrule)), 'RRULE:')) {
            throw new InvalidTaskScheduleException(\sprintf('rrule is written without the "RRULE:" prefix: "%s".', substr(ltrim($rrule), 6)));
        }

        try {
            $rule = new Rule($rrule, new \DateTimeImmutable('today', new \DateTimeZone('UTC')), null, 'UTC');
        } catch (\Throwable) {
            throw new InvalidTaskScheduleException(\sprintf('rrule "%s" cannot be read: it is an RFC 5545 rule without the "RRULE:" prefix, e.g. FREQ=WEEKLY;INTERVAL=2;BYDAY=TU.', $rrule));
        }

        if (null !== $rule->getCount()) {
            throw new InvalidTaskScheduleException(\sprintf('rrule "%s" has a COUNT, which a task does not take: end the series with UNTIL=YYYYMMDD instead.', $rrule));
        }
        if (!\in_array($rule->getFreq(), self::FREQUENCIES, true)) {
            throw new InvalidTaskScheduleException(\sprintf('rrule "%s" repeats within a day: a task repeats by days, FREQ is DAILY, WEEKLY, MONTHLY or YEARLY.', $rrule));
        }
    }

    /**
     * The days of $rrule from $start, $start counted only if it is one of them.
     *
     * @return list<\DateTimeImmutable>
     */
    private static function days(string $rrule, \DateTimeImmutable $start): array
    {
        self::parse($rrule);
        $config = new ArrayTransformerConfig();
        $config->enableLastDayOfMonthFix();
        $transformer = new ArrayTransformer();
        $transformer->setConfig($config);

        $days = [];
        foreach ($transformer->transform(new Rule($rrule, $start, null, 'UTC')) as $recurrence) {
            $days[] = self::day($recurrence->getStart());
        }

        return $days;
    }

    private static function day(\DateTimeInterface $day): \DateTimeImmutable
    {
        return new \DateTimeImmutable($day->format('Y-m-d'), new \DateTimeZone('UTC'));
    }
}
