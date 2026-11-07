<?php

declare(strict_types=1);

namespace Maggie\Health\Event;

/**
 * A food intake was recorded, whichever door it came through (MAG-748): read
 * by the health journal (MAG-750). Identifiers and the day only.
 */
final readonly class FoodIntakeRecorded
{
    /** @param string $date the local day the intake counts for, YYYY-MM-DD */
    public function __construct(
        public string $foodIntakeId,
        public string $userId,
        public string $date,
    ) {
    }
}
