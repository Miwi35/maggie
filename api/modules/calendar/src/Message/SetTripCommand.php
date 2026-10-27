<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

use Maggie\Calendar\Enum\TripDecider;
use Maggie\Calendar\Enum\TripMode;

/** Computes the trip to an event and keeps it, replacing the one it had (MAG-654). */
final readonly class SetTripCommand
{
    /** @param string $origin `position`, `place:<ulid>` or `event:<ulid>` */
    public function __construct(
        public string $userId,
        public string $eventId,
        public TripMode $mode,
        public string $origin,
        public TripDecider $decidedBy = TripDecider::Maggie,
    ) {
    }
}
