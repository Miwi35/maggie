<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Routine;
use Maggie\Calendar\Entity\RoutineCheck;
use Maggie\Calendar\Enum\DayMoment;
use Maggie\Calendar\Repository\RoutineCheckRepository;
use Maggie\Calendar\Repository\RoutineRepository;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Core\Entity\User;
use Symfony\Component\String\UnicodeString;

/**
 * Routines as every screen reads them (MAG-511): due or not on a day, at
 * which hour, and what was done — the ticks of the month and since the
 * creation, the last seven days. No streak, anywhere: a day missed shows no
 * more than a day off.
 *
 * Days are the user's own ({@see TaskOverdue::today()}), as midnight UTC.
 */
final class RoutineSchedule
{
    private const RECENT_DAYS = 7;

    public function __construct(
        private readonly RoutineRepository $routines,
        private readonly RoutineCheckRepository $checks,
        private readonly TaskSettingsRepository $settings,
        private readonly TaskOverdue $overdue,
    ) {
    }

    public function today(User $user): \DateTimeImmutable
    {
        return $this->overdue->today($user);
    }

    /** The local day the routine was created on: no tick before it. */
    public function createdOn(Routine $routine): \DateTimeImmutable
    {
        $local = $routine->getCreatedAt()->setTimezone($this->overdue->zone($routine->getUser()));

        return DayPlans::day($local->format('Y-m-d'));
    }

    /**
     * Due on that day: active, created by then, and on its rhythm. A monthly
     * routine on the 31st falls on the last day of a shorter month.
     */
    public function isDue(Routine $routine, \DateTimeImmutable $day): bool
    {
        if (!$routine->isActive() || $day < $this->createdOn($routine)) {
            return false;
        }

        $recurrence = $routine->getRecurrence();

        return match ($recurrence['kind']) {
            Routine::WEEKDAYS => (int) $day->format('N') <= 5,
            Routine::WEEKLY => (int) $day->format('N') === ($recurrence['weekday'] ?? 0),
            Routine::MONTHLY => (int) $day->format('j') === min($recurrence['monthDay'] ?? 0, (int) $day->format('t')),
            default => true,
        };
    }

    /**
     * The hour of the routine, `HH:MM`: its moment's in the user's settings, or its own.
     *
     * @param array<string, string> $moments the user's {@see \Maggie\Calendar\Entity\TaskSettings::getDayMoments()}
     */
    public static function time(Routine $routine, array $moments): string
    {
        return $routine->getAnchorTime() ?? $moments[$routine->getAnchor()] ?? DayMoment::DEFAULTS[$routine->getAnchor()] ?? '00:00';
    }

    /** @return array<string, string> */
    public function moments(User $user): array
    {
        return $this->settings->findOneByUser($user)?->getDayMoments() ?? DayMoment::DEFAULTS;
    }

    /**
     * Every routine of the user, active or not, in the order of the day — by
     * hour, then title.
     *
     * @return list<array<string, mixed>>
     */
    public function describeAll(User $user): array
    {
        $routines = $this->routines->findByUser($user);
        $checks = $this->checks->findByRoutines($routines);
        $moments = $this->moments($user);
        $today = $this->today($user);

        return array_map(
            fn (Routine $routine): array => $this->read($routine, $checks[(string) $routine->getId()] ?? [], $moments, $today),
            self::sorted($routines, $moments),
        );
    }

    /** @return array<string, mixed> */
    public function describe(Routine $routine): array
    {
        $user = $routine->getUser();

        return $this->read($routine, $this->checks->findByRoutine($routine), $this->moments($user), $this->today($user));
    }

    /**
     * The active routines due that day, grouped by moment in the order of the
     * day; each says whether it was ticked that day. Routines at an hour are
     * grouped by that hour.
     *
     * @return array{date: string, moments: list<array{anchor: string, time: string, routines: list<array<string, mixed>>}>}
     */
    public function day(User $user, \DateTimeImmutable $day): array
    {
        $moments = $this->moments($user);
        $today = $this->today($user);
        $due = array_values(array_filter($this->routines->findByUser($user), fn (Routine $routine): bool => $this->isDue($routine, $day)));
        $checks = $this->checks->findByRoutines($due);

        $groups = [];
        foreach (self::sorted($due, $moments) as $routine) {
            $time = self::time($routine, $moments);
            $key = $routine->getAnchor().'@'.$time;
            $groups[$key] ??= ['anchor' => $routine->getAnchor(), 'time' => $time, 'routines' => []];
            $routineChecks = $checks[(string) $routine->getId()] ?? [];
            $groups[$key]['routines'][] = $this->read($routine, $routineChecks, $moments, $today) + [
                'checked' => self::checkedOn($routineChecks, $day),
            ];
        }

        return ['date' => $day->format('Y-m-d'), 'moments' => array_values($groups)];
    }

    /**
     * @param list<RoutineCheck>    $checks
     * @param array<string, string> $moments
     *
     * @return array<string, mixed>
     */
    private function read(Routine $routine, array $checks, array $moments, \DateTimeImmutable $today): array
    {
        $month = $today->format('Y-m');
        $monthCount = \count(array_filter($checks, static fn (RoutineCheck $check): bool => $check->getDate()->format('Y-m') === $month));

        $recentDays = [];
        for ($ago = self::RECENT_DAYS - 1; $ago >= 0; --$ago) {
            $day = $today->modify("-{$ago} days");
            $recentDays[] = [
                'date' => $day->format('Y-m-d'),
                'due' => $this->isDue($routine, $day),
                'checked' => self::checkedOn($checks, $day),
            ];
        }

        return [
            'id' => (string) $routine->getId(),
            'title' => $routine->getTitle(),
            'anchor' => $routine->getAnchor(),
            'anchorTime' => $routine->getAnchorTime(),
            'time' => self::time($routine, $moments),
            'recurrence' => $routine->getRecurrence(),
            'active' => $routine->isActive(),
            'remind' => $routine->isRemind(),
            'createdAt' => $routine->getCreatedAt()->format(\DATE_ATOM),
            'checkedToday' => self::checkedOn($checks, $today),
            'dueToday' => $this->isDue($routine, $today),
            'monthCount' => $monthCount,
            'totalCount' => \count($checks),
            'recentDays' => $recentDays,
        ];
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

    /**
     * @param list<Routine>         $routines
     * @param array<string, string> $moments
     *
     * @return list<Routine>
     */
    private static function sorted(array $routines, array $moments): array
    {
        // « Aérer » before « Allumer »: titles compared without their accents.
        $key = static fn (Routine $routine): array => [self::time($routine, $moments), (new UnicodeString($routine->getTitle()))->ascii()->lower()->toString()];
        usort($routines, static fn (Routine $a, Routine $b): int => $key($a) <=> $key($b));

        return $routines;
    }
}
