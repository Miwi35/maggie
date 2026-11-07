<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Core\Entity\User;
use Maggie\Health\Dto\HealthDayFigures;
use Maggie\Health\Repository\BodyMeasurementRepository;
use Maggie\Health\Repository\DailyActivityRepository;
use Maggie\Health\Repository\FoodIntakeRepository;
use Maggie\Health\Repository\WorkoutRepository;

/**
 * A health journal day's computed columns (MAG-750), read from the detailed
 * tables — the single place they are computed, for the events and for the
 * rebuild alike:
 *
 * - weight and body fat: the day's first weighing not set aside, both from it;
 *   a day without one stays empty, never the day before's;
 * - steps: Health Connect's day;
 * - eaten kcal: the day's intakes, done only (the planned meals are today's
 *   live balance, not the journal's);
 * - expended kcal: the consolidated expenditure of {@see DailyEnergyReader};
 * - sport minutes: the done sessions that started that day.
 *
 * A figure without any source is null. The day is local: midnight to
 * midnight, Europe/Paris.
 */
final class HealthDayCalculator
{
    public function __construct(
        private readonly BodyMeasurementRepository $measurements,
        private readonly DailyActivityRepository $activities,
        private readonly FoodIntakeRepository $intakes,
        private readonly WorkoutRepository $workouts,
        private readonly DailyEnergyReader $energy,
    ) {
    }

    public function compute(User $user, \DateTimeImmutable $day): HealthDayFigures
    {
        $day = $day->setTime(0, 0);
        $weighing = $this->measurements->firstOfDay($user, $day);
        $activity = $this->activities->findOneByDate($user, $day);

        $intakes = $this->intakes->findInRange($user, $day, $day);
        $eatenKcal = [] === $intakes ? null : array_sum(array_map(static fn ($intake): int => (int) $intake->getKcal(), $intakes));

        $workouts = $this->workouts->findDoneInRange($user, $day, $day->modify('+1 day'));
        $sportMinutes = [] === $workouts ? null : array_sum(array_map(static fn ($workout): int => $workout->getDurationMinutes(), $workouts));

        $date = $day->format('Y-m-d');

        return new HealthDayFigures(
            weightKg: $weighing?->getWeightKg(),
            bodyFatPercent: $weighing?->getBodyFatPercent(),
            weighedAt: $weighing?->getMeasuredAt(),
            steps: $activity?->getSteps(),
            eatenKcal: $eatenKcal,
            expendedKcal: $this->energy->read($user, $date, $date)[0]['expendedKcal'],
            sportMinutes: $sportMinutes,
            hasSource: null !== $weighing || null !== $activity || [] !== $intakes || [] !== $workouts,
        );
    }
}
