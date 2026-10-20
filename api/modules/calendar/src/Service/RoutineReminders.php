<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Routine;
use Maggie\Calendar\Entity\RoutineCheck;
use Maggie\Calendar\Enum\DayMoment;
use Maggie\Calendar\Repository\RoutineCheckRepository;
use Maggie\Calendar\Repository\RoutineRepository;
use Maggie\Core\Entity\User;

/**
 * What a routine reminder is about (MAG-512): the routines that ask to be
 * reminded (`remind`), due on a day and not ticked yet, grouped by moment —
 * one reminder per moment, and per hour for the routines anchored to one.
 *
 * A group is known by its key, `wake` for a moment of the day and
 * `time@HH:MM` for an hour; the reminder's subject is `{date}:{key}`.
 * {@see TaskReminders} plans, composes and answers them.
 */
final class RoutineReminders
{
    public function __construct(
        private readonly RoutineRepository $routines,
        private readonly RoutineCheckRepository $checks,
        private readonly RoutineSchedule $schedule,
    ) {
    }

    /**
     * The groups of routines to remind on that day that still wait for a tick,
     * in the order of the day.
     *
     * @return list<array{key: string, moment: DayMoment|null, time: string, routines: list<Routine>}>
     */
    public function groups(User $user, \DateTimeImmutable $day): array
    {
        $due = array_values(array_filter(
            $this->routines->findByUser($user),
            fn (Routine $routine): bool => $routine->isRemind() && $this->schedule->isDue($routine, $day),
        ));
        $checks = $this->checks->findByRoutines($due);
        $moments = $this->schedule->moments($user);

        $groups = [];
        foreach ($due as $routine) {
            if (self::checkedOn($checks[(string) $routine->getId()] ?? [], $day)) {
                continue;
            }
            $key = self::keyOf($routine, $moments);
            $groups[$key] ??= [
                'key' => $key,
                'moment' => DayMoment::tryFrom($routine->getAnchor()),
                'time' => RoutineSchedule::time($routine, $moments),
                'routines' => [],
            ];
            $groups[$key]['routines'][] = $routine;
        }

        uasort($groups, static fn (array $a, array $b): int => [$a['time'], $a['key']] <=> [$b['time'], $b['key']]);
        foreach ($groups as &$group) {
            usort($group['routines'], static fn (Routine $a, Routine $b): int => $a->getTitle() <=> $b->getTitle());
        }

        return array_values($groups);
    }

    /**
     * One group, with the routines still to tick; null when all are done, or
     * the group no longer exists.
     *
     * @return array{key: string, moment: DayMoment|null, time: string, routines: list<Routine>}|null
     */
    public function group(User $user, \DateTimeImmutable $day, string $key): ?array
    {
        foreach ($this->groups($user, $day) as $group) {
            if ($group['key'] === $key) {
                return $group;
            }
        }

        return null;
    }

    /** `{date}:{key}`, what a routine reminder is about. */
    public static function subject(\DateTimeImmutable $day, string $key): string
    {
        return $day->format('Y-m-d').':'.$key;
    }

    /**
     * @return array{\DateTimeImmutable, string}
     *
     * @throws \InvalidArgumentException on a subject that is not `{date}:{key}`
     */
    public static function parse(string $subject): array
    {
        $parts = explode(':', $subject, 2);
        if (2 !== \count($parts) || '' === $parts[1]) {
            throw new \InvalidArgumentException(\sprintf('"%s" is not a routine reminder subject, {date}:{moment} expected.', $subject));
        }

        return [DayPlans::day($parts[0]), $parts[1]];
    }

    /** « matin », « après-midi » or « soir »: the part of the day the hour is in, as the Routines screen groups them. */
    public static function dayPart(string $time): string
    {
        return match (true) {
            $time < '12:00' => 'matin',
            $time < '18:00' => 'après-midi',
            default => 'soir',
        };
    }

    /** @param array<string, string> $moments */
    private static function keyOf(Routine $routine, array $moments): string
    {
        return Routine::ANCHOR_TIME === $routine->getAnchor()
            ? 'time@'.RoutineSchedule::time($routine, $moments)
            : $routine->getAnchor();
    }

    /** @param list<RoutineCheck> $checks */
    private static function checkedOn(array $checks, \DateTimeImmutable $day): bool
    {
        $date = $day->format('Y-m-d');
        foreach ($checks as $check) {
            if ($check->getDate()->format('Y-m-d') === $date) {
                return true;
            }
        }

        return false;
    }
}
