<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Place;
use Maggie\Calendar\Repository\PlaceRepository;
use Maggie\Core\Entity\User;

/**
 * The known place a point stands at (MAG-652): the user's nearest place within
 * {@see self::RADIUS_METERS}, as the crow flies. Read by the trips (« Partir à
 * l'heure », parts 4 and 5) to recognise a geocoded event location.
 */
final readonly class PlaceMatcher
{
    public const float RADIUS_METERS = 150.0;
    private const float EARTH_RADIUS_METERS = 6_371_000.0;

    public function __construct(private PlaceRepository $repository)
    {
    }

    /** Null when none of the user's located places is within the radius. */
    public function match(User $user, float $latitude, float $longitude): ?Place
    {
        $nearest = null;
        $nearestDistance = self::RADIUS_METERS;

        foreach ($this->repository->findLocatedByUser($user) as $place) {
            $distance = self::distance($latitude, $longitude, (float) $place->getLatitude(), (float) $place->getLongitude());
            if ($distance <= $nearestDistance) {
                $nearest = $place;
                $nearestDistance = $distance;
            }
        }

        return $nearest;
    }

    /** Haversine, in meters. */
    public static function distance(float $fromLatitude, float $fromLongitude, float $toLatitude, float $toLongitude): float
    {
        $dLatitude = deg2rad($toLatitude - $fromLatitude);
        $dLongitude = deg2rad($toLongitude - $fromLongitude);
        $a = sin($dLatitude / 2) ** 2
            + cos(deg2rad($fromLatitude)) * cos(deg2rad($toLatitude)) * sin($dLongitude / 2) ** 2;

        return 2 * self::EARTH_RADIUS_METERS * asin(min(1.0, sqrt($a)));
    }
}
