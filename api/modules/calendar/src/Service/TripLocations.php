<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Place;
use Maggie\Calendar\Exception\InvalidTripException;
use Maggie\Calendar\Routing\AddressNotFoundException;
use Maggie\Calendar\Routing\GeocodingClient;
use Maggie\Calendar\Routing\RoutingException;

/**
 * The coordinates a trip needs (MAG-653), geocoded once and stored: on the
 * event (`locationLat`, `locationLon`, cleared when its location changes) or
 * on the place (its address, its parking).
 */
class TripLocations
{
    public function __construct(
        private readonly GeocodingClient $geocoding,
        private readonly EntityManagerInterface $entityManager,
    ) {
    }

    /**
     * @return array{float, float}
     *
     * @throws InvalidTripException the event has no location, or Google finds nothing there
     * @throws RoutingException
     */
    public function ofEvent(Event $event, string $field = 'eventId'): array
    {
        if (null !== $event->getLocationLat() && null !== $event->getLocationLon()) {
            return [$event->getLocationLat(), $event->getLocationLon()];
        }

        $location = trim((string) $event->getLocation());
        if ('' === $location) {
            throw new InvalidTripException($field, sprintf('« %s » has no location: set one with update_event first.', $event->getSummary()));
        }

        [$latitude, $longitude] = $this->geocode($location, $field);
        $event->locateAt($latitude, $longitude);
        $this->entityManager->flush();

        return [$latitude, $longitude];
    }

    /**
     * @return array{float, float}
     *
     * @throws InvalidTripException Google finds nothing at the place's address
     * @throws RoutingException
     */
    public function ofPlace(Place $place, string $field = 'origin'): array
    {
        if (!$place->hasCoordinates()) {
            [$latitude, $longitude] = $this->geocode($place->getAddress(), $field);
            $place->setCoordinates($latitude, $longitude);
            $this->entityManager->flush();
        }

        return [(float) $place->getLatitude(), (float) $place->getLongitude()];
    }

    /**
     * Null when the place has no parking; a parking known by its address only is geocoded.
     *
     * @return array{float, float}|null
     *
     * @throws RoutingException
     */
    public function ofParking(Place $place): ?array
    {
        if (null === $place->getParkingLatitude() || null === $place->getParkingLongitude()) {
            $address = trim((string) $place->getParkingAddress());
            if ('' === $address) {
                return null;
            }

            try {
                [$latitude, $longitude] = $this->geocode($address, 'parking');
            } catch (InvalidTripException) {
                // A parking Google cannot find is a parking unknown, not a trip refused.
                return null;
            }
            $place->setParkingCoordinates($latitude, $longitude);
            $this->entityManager->flush();
        }

        return [(float) $place->getParkingLatitude(), (float) $place->getParkingLongitude()];
    }

    /**
     * @return array{float, float}
     *
     * @throws InvalidTripException
     * @throws RoutingException
     */
    private function geocode(string $address, string $field): array
    {
        try {
            $found = $this->geocoding->geocode($address);
        } catch (AddressNotFoundException) {
            throw new InvalidTripException($field, sprintf('Google finds no place at « %s »: correct the address.', $address));
        }

        return [$found['latitude'], $found['longitude']];
    }
}
