<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Place;
use Maggie\Calendar\Exception\InvalidPlaceException;

/**
 * Applies what `save_place` sent to a place (MAG-652). Everything is checked
 * before anything is set: a refused save changes nothing.
 *
 * `name` and `address` always. Coordinates go by pairs, within bounds. Left
 * out, an optional field keeps its value — except the coordinates of an
 * address that changed, which belonged to the old one and are dropped. An
 * empty `parkingAddress` removes the parking.
 */
final class PlaceInput
{
    /** @param array{name?: ?string, address?: ?string, latitude?: ?float, longitude?: ?float, parkingAddress?: ?string, parkingLatitude?: ?float, parkingLongitude?: ?float, isHome?: ?bool} $fields */
    public static function apply(Place $place, array $fields): void
    {
        $name = self::text($fields['name'] ?? null, 'name', 255);
        $address = self::text($fields['address'] ?? null, 'address', 500);
        [$latitude, $longitude] = self::pair($fields['latitude'] ?? null, $fields['longitude'] ?? null, 'latitude', 'longitude');
        [$parkingLatitude, $parkingLongitude] = self::pair($fields['parkingLatitude'] ?? null, $fields['parkingLongitude'] ?? null, 'parkingLatitude', 'parkingLongitude');

        $parkingAddress = $fields['parkingAddress'] ?? null;
        $removesParking = null !== $parkingAddress && '' === trim($parkingAddress);
        if (null !== $parkingAddress && !$removesParking) {
            $parkingAddress = self::text($parkingAddress, 'parkingAddress', 500);
        }
        if (null !== $parkingLatitude && ($removesParking || null === ($parkingAddress ?? $place->getParkingAddress()))) {
            throw new InvalidPlaceException('parkingAddress', 'parking coordinates need the parking\'s address.');
        }

        if (null === $latitude && $address !== $place->getAddress()) {
            $place->setCoordinates(null, null);
        }
        $place->setName($name)->setAddress($address);
        if (null !== $latitude) {
            $place->setCoordinates($latitude, $longitude);
        }

        if ($removesParking) {
            $place->setParkingAddress(null)->setParkingCoordinates(null, null);
        } else {
            if (null !== $parkingAddress && null === $parkingLatitude && $parkingAddress !== $place->getParkingAddress()) {
                $place->setParkingCoordinates(null, null);
            }
            if (null !== $parkingAddress) {
                $place->setParkingAddress($parkingAddress);
            }
            if (null !== $parkingLatitude) {
                $place->setParkingCoordinates($parkingLatitude, $parkingLongitude);
            }
        }

        if (isset($fields['isHome'])) {
            $place->setIsHome($fields['isHome']);
        }
    }

    private static function text(?string $value, string $field, int $max): string
    {
        $value = trim($value ?? '');
        if ('' === $value) {
            throw new InvalidPlaceException($field, 'required.');
        }
        if (mb_strlen($value) > $max) {
            throw new InvalidPlaceException($field, "{$max} characters at most.");
        }

        return $value;
    }

    /** @return array{?float, ?float} both or neither, within bounds */
    private static function pair(?float $latitude, ?float $longitude, string $latitudeField, string $longitudeField): array
    {
        if (null === $latitude xor null === $longitude) {
            throw new InvalidPlaceException(null === $latitude ? $latitudeField : $longitudeField, "{$latitudeField} and {$longitudeField} go together: give both or neither.");
        }
        if (null !== $latitude && ($latitude < -90 || $latitude > 90)) {
            throw new InvalidPlaceException($latitudeField, 'from -90 to 90.');
        }
        if (null !== $longitude && ($longitude < -180 || $longitude > 180)) {
            throw new InvalidPlaceException($longitudeField, 'from -180 to 180.');
        }

        return [$latitude, $longitude];
    }
}
