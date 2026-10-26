<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp;

use Maggie\Calendar\Entity\Place;

/** A place as `save_place` and `list_places` return it (MAG-652). */
final class PlaceJson
{
    /** @return array<string, mixed> */
    public static function of(Place $place): array
    {
        return [
            'id' => (string) $place->getId(),
            'name' => $place->getName(),
            'address' => $place->getAddress(),
            'latitude' => $place->getLatitude(),
            'longitude' => $place->getLongitude(),
            'parkingAddress' => $place->getParkingAddress(),
            'parkingLatitude' => $place->getParkingLatitude(),
            'parkingLongitude' => $place->getParkingLongitude(),
            'isHome' => $place->isHome(),
        ];
    }
}
