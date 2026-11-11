<?php

declare(strict_types=1);

namespace Maggie\Health\Specification;

use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\WorkoutSource;

/** A planned session marked as done (its source changed): it leaves the agenda (MAG-777). */
final readonly class IsWorkoutNoLongerPlanned
{
    /** @param array<string, array{0: mixed, 1: mixed}> $changeSet what Doctrine is about to write: field => [before, after] */
    public function __construct(
        private array $changeSet,
    ) {
    }

    public function isSatisfiedBy(Workout $workout): bool
    {
        if (!isset($this->changeSet['source'])) {
            return false;
        }

        // Doctrine hands the previous value over as the column's string; a creation has none.
        $before = $this->changeSet['source'][0];
        $before = $before instanceof WorkoutSource ? $before : WorkoutSource::tryFrom((string) $before);

        return WorkoutSource::Planned === $before && !$workout->isPlanned();
    }
}
