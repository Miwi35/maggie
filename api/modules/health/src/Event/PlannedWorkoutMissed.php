<?php

declare(strict_types=1);

namespace Maggie\Health\Event;

/** A planned session ended half an hour ago and nothing kept it (MAG-771). */
final readonly class PlannedWorkoutMissed
{
    public function __construct(
        public string $plannedWorkoutId,
    ) {
    }
}
