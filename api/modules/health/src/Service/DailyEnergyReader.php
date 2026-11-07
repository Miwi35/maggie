<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Core\Entity\User;
use Maggie\Health\Repository\DailyActivityRepository;
use Maggie\Health\Repository\WorkoutRepository;

/**
 * Santé › Suivi's daily energy (MAG-746), as the endpoint and the tool return
 * it: one row per local day (Paris, like every date the user gives) from the
 * profile's basal metabolism, Health Connect's day and the done sessions.
 */
final class DailyEnergyReader
{
    public const TIME_ZONE = 'Europe/Paris';

    public const MAX_DAYS = 400;

    public function __construct(
        private readonly DailyActivityRepository $days,
        private readonly WorkoutRepository $workouts,
        private readonly HealthProfileReader $profiles,
    ) {
    }

    /** @throws \InvalidArgumentException naming the parameter, for the caller to refuse */
    public static function day(string $name, string $value): \DateTimeImmutable
    {
        $zone = new \DateTimeZone(self::TIME_ZONE);
        $date = \DateTimeImmutable::createFromFormat('!Y-m-d', $value, $zone);
        if (false === $date || $date->format('Y-m-d') !== $value) {
            throw new \InvalidArgumentException("{$name} must be a date YYYY-MM-DD.");
        }

        return $date;
    }

    /**
     * @return list<array{date: string, steps: ?int, activeKcal: ?int, bmrKcal: ?int, workoutKcal: int, stepsKcal: ?int, expendedKcal: ?int}>
     *
     * @throws \InvalidArgumentException
     */
    public function read(User $user, string $from, string $to): array
    {
        $start = self::day('from', $from);
        $last = self::day('to', $to);
        if ($start > $last) {
            throw new \InvalidArgumentException('from must not be after to.');
        }
        if ($start->diff($last)->days >= self::MAX_DAYS) {
            throw new \InvalidArgumentException('The period is at most '.self::MAX_DAYS.' days.');
        }

        $profile = $this->profiles->describe($user);
        $weightKg = $profile['weightKg'];
        $bmrKcal = $profile['bmrKcal'];

        $activities = [];
        foreach ($this->days->findInRange($user, $start, $last) as $activity) {
            $activities[$activity->getDate()->format('Y-m-d')] = $activity;
        }

        $zone = new \DateTimeZone(self::TIME_ZONE);
        $byDay = [];
        foreach ($this->workouts->findDoneInRange($user, $start, $last->modify('+1 day')) as $workout) {
            $key = $workout->getStartedAt()->setTimezone($zone)->format('Y-m-d');
            $byDay[$key][] = [
                'kcal' => $workout->getKcal() ?? DailyEnergyCalculator::estimateKcal($workout->getType(), $workout->getDurationMinutes(), $weightKg),
                'seenByHealthConnect' => null !== $workout->getExternalId(),
            ];
        }

        $rows = [];
        for ($day = $start; $day <= $last; $day = $day->modify('+1 day')) {
            $key = $day->format('Y-m-d');
            $activity = $activities[$key] ?? null;
            $rows[] = ['date' => $key] + DailyEnergyCalculator::day($activity?->getSteps(), $activity?->getActiveKcal(), $bmrKcal, $byDay[$key] ?? []);
        }

        return $rows;
    }
}
