<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Health\Enum\WorkoutType;

/**
 * The day's consolidated energy (MAG-746), the single place its arithmetic
 * lives. Health Connect de-duplicates the day's steps and active kcal and the
 * sessions Maggie writes there are inside `activeKcal`: a session with an
 * `externalId` is *seen* by it and never added again, a session without one
 * (typed by hand, not yet in Health Connect) adds its own figure — its kcal,
 * else MET × weight × duration. A figure that is missing gives null.
 */
final class DailyEnergyCalculator
{
    /** The compendium's MET of each kind of session, for a session without kcal. */
    private const MET = [
        'run' => 9.8,
        'walk' => 3.8,
        'bike' => 7.5,
        'swim' => 8.0,
        'strength' => 5.0,
        'climbing' => 8.0,
        'other' => 4.0,
    ];

    /** MET × weight × hours, rounded; null while the weight is unknown. */
    public static function estimateKcal(WorkoutType $type, int $durationMinutes, ?float $weightKg): ?int
    {
        return null === $weightKg ? null : (int) round(self::MET[$type->value] * $weightKg * $durationMinutes / 60);
    }

    /**
     * @param list<array{kcal: ?int, seenByHealthConnect: bool}> $workouts the day's done sessions, kcal already estimated when unknown
     *
     * @return array{steps: ?int, activeKcal: ?int, bmrKcal: ?int, workoutKcal: int, stepsKcal: ?int, expendedKcal: ?int}
     */
    public static function day(?int $steps, ?int $activeKcal, ?int $bmrKcal, array $workouts): array
    {
        $workoutKcal = 0;
        $seenKcal = 0;
        foreach ($workouts as $workout) {
            $kcal = $workout['kcal'] ?? 0;
            $workoutKcal += $kcal;
            if ($workout['seenByHealthConnect']) {
                $seenKcal += $kcal;
            }
        }
        $unseenKcal = $workoutKcal - $seenKcal;
        $active = max($activeKcal ?? 0, $seenKcal);

        return [
            'steps' => $steps,
            'activeKcal' => $activeKcal,
            'bmrKcal' => $bmrKcal,
            'workoutKcal' => $workoutKcal,
            'stepsKcal' => null === $activeKcal ? null : max(0, $activeKcal - $seenKcal),
            'expendedKcal' => null === $bmrKcal ? null : $bmrKcal + $active + $unseenKcal,
        ];
    }
}
