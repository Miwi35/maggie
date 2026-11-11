<?php

declare(strict_types=1);

namespace Maggie\Health\Dto;

use Maggie\Health\Enum\ExerciseFeeling;
use Maggie\Health\Enum\WorkoutVariant;

/**
 * One exercise as it was done in one session (MAG-776): what the progression
 * rules read. `counts` are the sets done, in the exercise's unit.
 */
final readonly class ExerciseSession
{
    /** @param list<int> $counts */
    public function __construct(
        public string $workoutId,
        public \DateTimeImmutable $at,
        public ?WorkoutVariant $variant,
        public int $step,
        public int $sets,
        public ?float $loadKg,
        public bool $tempo,
        public ?ExerciseFeeling $feeling,
        public array $counts,
    ) {
    }

    public function total(): int
    {
        return array_sum($this->counts);
    }

    public function lowest(): int
    {
        return [] === $this->counts ? 0 : min($this->counts);
    }
}
