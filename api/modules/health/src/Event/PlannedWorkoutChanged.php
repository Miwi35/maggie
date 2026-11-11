<?php

declare(strict_types=1);

namespace Maggie\Health\Event;

/** A planned session was created, or its title, start, duration or source changed (MAG-777). */
final readonly class PlannedWorkoutChanged
{
    public function __construct(
        public string $userId,
        public string $workoutId,
    ) {
    }
}
