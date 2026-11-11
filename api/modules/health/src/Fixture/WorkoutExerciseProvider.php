<?php

declare(strict_types=1);

namespace Maggie\Health\Fixture;

use Maggie\Health\Dto\WorkoutExerciseInput;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Service\ExerciseCatalog;
use Maggie\Health\Service\WorkoutRules;

/**
 * Makes `<workoutExercise("goblet-squat", 5, 3, 10, 8, 12, 90, 10)>` usable in
 * the fixture files (MAG-775), for `Workout::replaceExercises()`: the exercise
 * goes through the same rules as a request, so a seed cannot hold one the API
 * would refuse. `done` is "8,8,7", the counts in the exercise's unit.
 * {@see self::of()} is the same for a loader without the provider
 * (`<(Maggie\Health\Fixture\WorkoutExerciseProvider::of(…))>`).
 */
final class WorkoutExerciseProvider
{
    public function workoutExercise(string $exercise, int $step, int $sets, int $target, int $targetMin, int $targetMax, int $restSeconds, ?float $loadKg = null, ?string $done = null, ?string $feeling = null): WorkoutExerciseInput
    {
        return self::of($exercise, $step, $sets, $target, $targetMin, $targetMax, $restSeconds, $loadKg, $done, $feeling);
    }

    public static function of(string $exercise, int $step, int $sets, int $target, int $targetMin, int $targetMax, int $restSeconds, ?float $loadKg = null, ?string $done = null, ?string $feeling = null): WorkoutExerciseInput
    {
        $item = ['exercise' => $exercise, 'step' => $step, 'sets' => $sets, 'target' => $target, 'targetMin' => $targetMin, 'targetMax' => $targetMax, 'restSeconds' => $restSeconds, 'loadKg' => $loadKg, 'feeling' => $feeling];
        $catalog = new ExerciseCatalog(\dirname(__DIR__, 2).'/config/reference');
        $rules = new WorkoutRules($catalog);
        $source = WorkoutSource::Planned;
        if (null !== $done) {
            $source = WorkoutSource::Manual;
            $unit = (string) ($catalog->exercise($exercise)['unit'] ?? 'reps');
            $item['done'] = array_map(static fn (string $count): array => [$unit => (int) $count] + (null === $loadKg ? [] : ['loadKg' => $loadKg]), explode(',', $done));
        }

        return $rules->exercises([$item], $source)[0];
    }
}
