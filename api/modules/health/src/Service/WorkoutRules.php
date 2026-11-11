<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Health\Dto\WorkoutExerciseInput;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\ExerciseFeeling;
use Maggie\Health\Enum\WorkoutFeeling;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutType;
use Maggie\Health\Enum\WorkoutVariant;
use Maggie\Health\Exception\InvalidWorkoutException;

/**
 * What a workout is held to whichever door it comes through (API, MCP).
 * Every refusal is an InvalidWorkoutException naming the field: a 400 for the
 * API, an error for the tool. Nothing is deduced.
 */
final class WorkoutRules
{
    public const MAX_EXERCISES = 30;

    public const MAX_SETS = 20;

    public const MAX_DONE_SETS = 50;

    /** Repetitions, seconds or metres: one bound for the three. */
    public const MAX_COUNT = 10_000;

    public const MAX_LOAD_KG = 500;

    public const MAX_REST_SECONDS = 900;

    public const MAX_RIR = 4;

    private const EXERCISE_KEYS = ['exercise', 'name', 'unit', 'step', 'sets', 'target', 'targetMin', 'targetMax', 'loadKg', 'restSeconds', 'tempo', 'targetRir', 'cue', 'feeling', 'done'];

    private const UNITS = ['reps', 'seconds', 'meters'];

    public function __construct(private readonly ExerciseCatalog $catalog)
    {
    }

    public function type(string $value): WorkoutType
    {
        return WorkoutType::tryFrom($value)
            ?? throw new InvalidWorkoutException('type', "unknown value \"{$value}\". Use run, walk, bike, swim, strength, climbing or other.");
    }

    public function feeling(?string $value): ?WorkoutFeeling
    {
        if (null === $value) {
            return null;
        }

        return WorkoutFeeling::tryFrom($value)
            ?? throw new InvalidWorkoutException('feeling', "unknown value \"{$value}\". Use very_good, good, hard or very_hard.");
    }

    public function variant(?string $value): ?WorkoutVariant
    {
        if (null === $value) {
            return null;
        }

        return WorkoutVariant::tryFrom($value)
            ?? throw new InvalidWorkoutException('variant', "unknown value \"{$value}\". Use normal, calibration, soft or deload.");
    }

    /** 1 to 10, on a session done only: a planned one has not been felt yet. */
    public function difficulty(?int $value, WorkoutSource $source): ?int
    {
        if (null === $value) {
            return null;
        }
        if (WorkoutSource::Planned === $source) {
            throw new InvalidWorkoutException('difficulty', 'is for a session done: a planned session has none.');
        }
        if ($value < Workout::MIN_DIFFICULTY || $value > Workout::MAX_DIFFICULTY) {
            throw new InvalidWorkoutException('difficulty', 'is a whole number from 1 to 10.');
        }

        return $value;
    }

    /**
     * The session's exercises, sent whole (MAG-775): each one checked against
     * the catalogue, nothing completed. `feeling` and `done` belong to a
     * session done; `name` and `unit` are read only and ignored.
     *
     * @param array<mixed> $exercises
     *
     * @return list<WorkoutExerciseInput>
     */
    public function exercises(array $exercises, WorkoutSource $source): array
    {
        if (!array_is_list($exercises)) {
            throw new InvalidWorkoutException('exercises', 'must be a list.');
        }
        if (\count($exercises) > self::MAX_EXERCISES) {
            throw new InvalidWorkoutException('exercises', 'holds at most '.self::MAX_EXERCISES.' exercises.');
        }

        $result = [];
        foreach ($exercises as $index => $item) {
            $result[] = $this->exercise("exercises[{$index}]", $item, WorkoutSource::Planned === $source);
        }

        return $result;
    }

    private function exercise(string $path, mixed $item, bool $planned): WorkoutExerciseInput
    {
        if (!\is_array($item) || ([] !== $item && array_is_list($item))) {
            throw new InvalidWorkoutException($path, 'must be an object {exercise, step, sets, target, targetMin, targetMax, restSeconds, …}.');
        }
        $unknown = array_diff(array_map('strval', array_keys($item)), self::EXERCISE_KEYS);
        if ([] !== $unknown) {
            throw new InvalidWorkoutException("{$path}.".reset($unknown), 'unknown field. Use '.implode(', ', self::EXERCISE_KEYS).'.');
        }

        $slug = $item['exercise'] ?? null;
        if (!\is_string($slug) || '' === $slug) {
            throw new InvalidWorkoutException("{$path}.exercise", 'is required: a slug of the exercise catalogue (get_exercises).');
        }
        $entry = $this->catalog->exercise($slug)
            ?? throw new InvalidWorkoutException("{$path}.exercise", "unknown exercise \"{$slug}\": use a slug of the exercise catalogue (get_exercises).");
        $loadable = true === $entry['loadable'];
        $unit = (string) $entry['unit'];

        $targetMin = $this->count($path, $item, 'targetMin', 1);
        $targetMax = $this->count($path, $item, 'targetMax', 1);
        if ($targetMin > $targetMax) {
            throw new InvalidWorkoutException("{$path}.targetMin", 'must not be above targetMax.');
        }

        $loadKg = $this->load("{$path}.loadKg", $item['loadKg'] ?? null, $loadable, $slug);

        $tempo = $item['tempo'] ?? false;
        if (!\is_bool($tempo)) {
            throw new InvalidWorkoutException("{$path}.tempo", 'is true or false.');
        }

        $targetRir = $item['targetRir'] ?? null;
        if (null !== $targetRir && (!\is_int($targetRir) || $targetRir < 0 || $targetRir > self::MAX_RIR)) {
            throw new InvalidWorkoutException("{$path}.targetRir", 'is a whole number from 0 to 4, or null.');
        }

        $cue = $item['cue'] ?? null;
        if (null !== $cue && (!\is_string($cue) || mb_strlen($cue) > 255)) {
            throw new InvalidWorkoutException("{$path}.cue", 'is a text of at most 255 characters, or null (the catalogue\'s then).');
        }

        $feeling = $item['feeling'] ?? null;
        if (null !== $feeling) {
            if ($planned) {
                throw new InvalidWorkoutException("{$path}.feeling", 'is for a session done: a planned session has not been felt yet.');
            }
            $feeling = \is_string($feeling) ? ExerciseFeeling::tryFrom($feeling) : null;
            if (null === $feeling) {
                throw new InvalidWorkoutException("{$path}.feeling", 'unknown value. Use too_easy, right or too_hard.');
            }
        }

        $done = $item['done'] ?? [];
        if (!\is_array($done) || !array_is_list($done)) {
            throw new InvalidWorkoutException("{$path}.done", 'must be a list of sets.');
        }
        if ($planned && [] !== $done) {
            throw new InvalidWorkoutException("{$path}.done", 'is for a session done: a planned session has no set done.');
        }
        if (\count($done) > self::MAX_DONE_SETS) {
            throw new InvalidWorkoutException("{$path}.done", 'holds at most '.self::MAX_DONE_SETS.' sets.');
        }
        $sets = [];
        foreach ($done as $index => $set) {
            $sets[] = $this->doneSet("{$path}.done[{$index}]", $set, $unit, $loadable, $slug);
        }

        return new WorkoutExerciseInput(
            exercise: $slug,
            name: (string) $entry['name'],
            unit: $unit,
            step: $this->count($path, $item, 'step', 1),
            sets: $this->count($path, $item, 'sets', 1, self::MAX_SETS),
            target: $this->count($path, $item, 'target', 1),
            targetMin: $targetMin,
            targetMax: $targetMax,
            loadKg: $loadKg,
            restSeconds: $this->count($path, $item, 'restSeconds', 0, self::MAX_REST_SECONDS),
            tempo: $tempo,
            targetRir: $targetRir,
            cue: $cue,
            feeling: $feeling,
            done: $sets,
        );
    }

    /** @param array<mixed> $item */
    private function count(string $path, array $item, string $key, int $min, int $max = self::MAX_COUNT): int
    {
        $value = $item[$key] ?? null;
        if (!\is_int($value) || $value < $min || $value > $max) {
            throw new InvalidWorkoutException("{$path}.{$key}", "is required, a whole number from {$min} to {$max}.");
        }

        return $value;
    }

    private function load(string $path, mixed $value, bool $loadable, string $slug): ?float
    {
        if (null === $value) {
            return null;
        }
        if (!$loadable) {
            throw new InvalidWorkoutException($path, "\"{$slug}\" takes no load: leave loadKg out.");
        }
        if ((!\is_int($value) && !\is_float($value)) || $value < 0 || $value > self::MAX_LOAD_KG) {
            throw new InvalidWorkoutException($path, 'is in kilograms, from 0 to '.self::MAX_LOAD_KG.'.');
        }

        return (float) $value;
    }

    /** @return array{reps: ?int, seconds: ?int, meters: ?int, loadKg: ?float} */
    private function doneSet(string $path, mixed $set, string $unit, bool $loadable, string $slug): array
    {
        if (!\is_array($set) || ([] !== $set && array_is_list($set))) {
            throw new InvalidWorkoutException($path, "must be an object {{$unit}, loadKg?}.");
        }
        foreach (array_keys($set) as $key) {
            if ('loadKg' === $key || $key === $unit) {
                continue;
            }
            if (\in_array($key, self::UNITS, true)) {
                throw new InvalidWorkoutException("{$path}.{$key}", "\"{$slug}\" counts in {$unit}: give {$unit}, not {$key}.");
            }
            throw new InvalidWorkoutException("{$path}.{$key}", "unknown field. Use {$unit} and loadKg.");
        }
        $count = $set[$unit] ?? null;
        if (!\is_int($count) || $count < 0 || $count > self::MAX_COUNT) {
            throw new InvalidWorkoutException("{$path}.{$unit}", 'is required, a whole number from 0 to '.self::MAX_COUNT.'.');
        }

        return [
            'reps' => 'reps' === $unit ? $count : null,
            'seconds' => 'seconds' === $unit ? $count : null,
            'meters' => 'meters' === $unit ? $count : null,
            'loadKg' => $this->load("{$path}.loadKg", $set['loadKg'] ?? null, $loadable, $slug),
        ];
    }

    public function source(string $value): WorkoutSource
    {
        return WorkoutSource::tryFrom($value)
            ?? throw new InvalidWorkoutException('source', "unknown value \"{$value}\". Use manual, watch, activity, estimated or planned.");
    }

    /** ISO 8601 with a time and an offset, e.g. 2026-10-20T07:10:00+02:00: a date alone or a time without offset is refused, never completed. */
    public function startedAt(string $value): \DateTimeImmutable
    {
        $hint = 'must be ISO 8601 with time and offset, e.g. 2026-10-20T07:10:00+02:00.';
        if (1 !== preg_match('/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})$/', $value)) {
            throw new InvalidWorkoutException('startedAt', $hint);
        }

        try {
            return new \DateTimeImmutable($value);
        } catch (\Exception) {
            throw new InvalidWorkoutException('startedAt', $hint);
        }
    }

    public function assert(string $title, int $durationMinutes, ?int $distanceMeters, ?int $kcal, ?int $avgHeartRate, ?string $notes = null, ?string $externalId = null): void
    {
        if ('' === trim($title)) {
            throw new InvalidWorkoutException('title', 'must not be blank.');
        }
        if (mb_strlen($title) > 255) {
            throw new InvalidWorkoutException('title', 'must be at most 255 characters.');
        }
        if ($durationMinutes < 1 || $durationMinutes > Workout::MAX_DURATION_MINUTES) {
            throw new InvalidWorkoutException('durationMinutes', 'is in whole minutes, from 1 to 1440.');
        }
        if (null !== $distanceMeters && ($distanceMeters < 0 || $distanceMeters > Workout::MAX_DISTANCE_METERS)) {
            throw new InvalidWorkoutException('distanceMeters', 'is in whole metres, from 0 to 1000000: 5.4 km is 5400.');
        }
        if (null !== $kcal && ($kcal < 0 || $kcal > Workout::MAX_KCAL)) {
            throw new InvalidWorkoutException('kcal', 'is in whole kilocalories, from 0 to 20000.');
        }
        if (null !== $avgHeartRate && ($avgHeartRate < Workout::MIN_HEART_RATE || $avgHeartRate > Workout::MAX_HEART_RATE)) {
            throw new InvalidWorkoutException('avgHeartRate', 'is in beats per minute, from 20 to 250.');
        }
        if (null !== $notes && mb_strlen($notes) > 2000) {
            throw new InvalidWorkoutException('notes', 'must be at most 2000 characters.');
        }
        if (null !== $externalId && mb_strlen($externalId) > 255) {
            throw new InvalidWorkoutException('externalId', 'must be at most 255 characters.');
        }
    }
}
