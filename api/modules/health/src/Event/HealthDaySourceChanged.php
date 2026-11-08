<?php

declare(strict_types=1);

namespace Maggie\Health\Event;

/**
 * A weighing, a session, an intake or Health Connect's day of this user and
 * local day was recorded, changed or removed (MAG-750): the health journal's
 * computed columns of that day must be read again. Identifiers and the day only.
 */
final readonly class HealthDaySourceChanged
{
    /** @param string $date the local day, YYYY-MM-DD */
    public function __construct(
        public string $userId,
        public string $date,
    ) {
    }
}
