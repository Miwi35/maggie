<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

use Maggie\Calendar\Enum\TripCarAt;
use Maggie\Calendar\Enum\TripDecider;
use Maggie\Calendar\Enum\TripLeg;
use Maggie\Calendar\Enum\TripMode;

/** Computes the trip to an event and keeps it, replacing the one it had (MAG-654). */
final readonly class SetTripCommand
{
    /**
     * @param string         $origin   `position`, `place:<ulid>` or `event:<ulid>`
     * @param string|null    $arriveAt ISO 8601, for a whole-day event only
     * @param string|null    $departAt ISO 8601, for a return only
     * @param string|null    $date     YYYY-MM-DD in Paris, the occurrence of a series (MAG-862)
     * @param TripCarAt|null $carAt    where the user said the car is, drive only (MAG-929)
     */
    public function __construct(
        public string $userId,
        public string $eventId,
        public TripMode $mode,
        public string $origin,
        public TripDecider $decidedBy = TripDecider::Maggie,
        public ?string $arriveAt = null,
        public TripLeg $leg = TripLeg::Outbound,
        public ?string $departAt = null,
        public ?string $date = null,
        public ?TripCarAt $carAt = null,
    ) {
    }
}
