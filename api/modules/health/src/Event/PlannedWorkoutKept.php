<?php

declare(strict_types=1);

namespace Maggie\Health\Event;

/** A session done keeps a planned one of its day (MAG-771). */
final readonly class PlannedWorkoutKept
{
    public function __construct(
        public string $doneWorkoutId,
        public string $plannedWorkoutId,
    ) {
    }
}
