<?php

declare(strict_types=1);

namespace Maggie\Calendar\Event;

/**
 * An event of today was created with a location, moved, relocated or removed (MAG-656): the trips
 * Maggie kept for the day may no longer hold. `change` says which, for the reason she is given.
 */
final readonly class EventTripsChangedEvent
{
    public const CREATED = 'created';
    public const UPDATED = 'updated';
    public const DELETED = 'deleted';

    /** @param self::CREATED|self::UPDATED|self::DELETED $change */
    public function __construct(
        public string $eventId,
        public string $userId,
        public string $change,
    ) {
    }
}
