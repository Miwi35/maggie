<?php

declare(strict_types=1);

namespace Maggie\Calendar\Specification;

use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Service\PlaceMatcher;
use Maggie\Core\Entity\UserLocation;
use Maggie\Core\Event\UserLocationChangedEvent;

/**
 * The user, or their car, is no longer where the trip was computed from (MAG-656): the position
 * more than {@see self::DISTANCE_METERS} from the trip's origin, or the car as far from the one
 * the trip took into account — a trip by car only: on foot or by transit, the car is no matter.
 * A car nobody knew the place of was taken where the trip starts (TripPlanner).
 */
final readonly class IsUserAwayFromTrip
{
    public const float DISTANCE_METERS = 300.0;

    /** @param UserLocationChangedEvent::POSITION|UserLocationChangedEvent::CAR $what */
    public function __construct(
        private UserLocation $location,
        private string $what,
    ) {
    }

    public function isSatisfiedBy(Trip $trip): bool
    {
        if (UserLocationChangedEvent::POSITION === $this->what) {
            return $this->isFar(
                $this->location->getLatitude(),
                $this->location->getLongitude(),
                $trip->getOriginLatitude(),
                $trip->getOriginLongitude(),
            );
        }

        return TripMode::Drive === $trip->getMode() && $this->isFar(
            $this->location->getCarLatitude(),
            $this->location->getCarLongitude(),
            $trip->getCarLatitude() ?? $trip->getOriginLatitude(),
            $trip->getCarLongitude() ?? $trip->getOriginLongitude(),
        );
    }

    private function isFar(?float $latitude, ?float $longitude, ?float $fromLatitude, ?float $fromLongitude): bool
    {
        if (null === $latitude || null === $longitude || null === $fromLatitude || null === $fromLongitude) {
            return false;
        }

        return PlaceMatcher::distance($latitude, $longitude, $fromLatitude, $fromLongitude) > self::DISTANCE_METERS;
    }
}
