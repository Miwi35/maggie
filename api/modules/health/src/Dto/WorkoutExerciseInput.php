<?php

declare(strict_types=1);

namespace Maggie\Health\Dto;

use Maggie\Health\Enum\ExerciseFeeling;

/**
 * One exercise of a session as {@see \Maggie\Health\Service\WorkoutRules::exercises()}
 * accepted it (MAG-775), with the catalogue's name and unit.
 */
final readonly class WorkoutExerciseInput
{
    /** @param list<array{reps: ?int, seconds: ?int, meters: ?int, loadKg: ?float}> $done */
    public function __construct(
        public string $exercise,
        public string $name,
        public string $unit,
        public int $step,
        public int $sets,
        public int $target,
        public int $targetMin,
        public int $targetMax,
        public ?float $loadKg,
        public int $restSeconds,
        public bool $tempo,
        public ?int $targetRir,
        public ?string $cue,
        public ?ExerciseFeeling $feeling,
        public array $done,
    ) {
    }
}
