<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Place;
use Maggie\Calendar\Exception\InvalidTripException;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Repository\PlaceRepository;
use Maggie\Calendar\Routing\RoutingException;
use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserLocationRepository;
use Maggie\Notification\Push\LocationRequester;
use Psr\Clock\ClockInterface;
use Symfony\Component\Uid\Ulid;

/**
 * Reads a trip's origin (MAG-653): `position` (the last one the phone gave),
 * `home` (the « Maison », taken from the Google account when none is saved),
 * `place:<ulid>` or `event:<ulid>` (where an earlier event is).
 *
 * « Origine fraîche »: a position older than {@see self::FRESH_POSITION_MINUTES}
 * makes the phones report a new one, and the trip is computed on the old one
 * meanwhile.
 */
class TripOrigins
{
    public const int FRESH_POSITION_MINUTES = 10;
    public const string HOME = 'home';

    public function __construct(
        private readonly UserLocationRepository $userLocations,
        private readonly PlaceRepository $places,
        private readonly EventRepository $events,
        private readonly TripLocations $locations,
        private readonly LocationRequester $locationRequester,
        private readonly ClockInterface $clock,
        private readonly HomeFromGoogle $homeFromGoogle,
    ) {
    }

    /**
     * @throws InvalidTripException a malformed or unknown origin, or no position known
     * @throws RoutingException
     */
    public function resolve(User $user, string $origin): TripOrigin
    {
        if (TripOrigin::POSITION === $origin) {
            return $this->position($user);
        }

        if (self::HOME === $origin) {
            return $this->home($user);
        }

        [$kind, $id] = array_pad(explode(':', $origin, 2), 2, '');
        if (!Ulid::isValid($id) || !\in_array($kind, [TripOrigin::PLACE, TripOrigin::EVENT], true)) {
            throw new InvalidTripException('origin', "« {$origin} » is not an origin: send position, home, place:<id> (list_places gives the ids) or event:<id>.");
        }

        return TripOrigin::PLACE === $kind ? $this->place($user, $id) : $this->event($user, $id);
    }

    private function position(User $user): TripOrigin
    {
        $location = $this->userLocations->findOneByUser($user);
        $latitude = $location?->getLatitude();
        $longitude = $location?->getLongitude();
        if (null === $location || null === $latitude || null === $longitude) {
            throw new InvalidTripException('origin', 'no position of the user is known yet: ask where they leave from, or use a place:<id> origin.');
        }

        $capturedAt = $location->getCapturedAt();
        $freshSince = $this->clock->now()->modify(sprintf('-%d minutes', self::FRESH_POSITION_MINUTES));
        $requested = false;
        if (null === $capturedAt || $capturedAt < $freshSince) {
            $this->locationRequester->request($user);
            $requested = true;
        }

        return new TripOrigin(TripOrigin::POSITION, null, 'ma position', $latitude, $longitude, $capturedAt, $requested);
    }

    private function home(User $user): TripOrigin
    {
        $home = $this->places->findHomes($user)[0] ?? $this->homeFromGoogle->importIfDue($user);
        if (null === $home) {
            throw new InvalidTripException('origin', 'no home is known: ask the user for their home address, then save_place it with isHome=true.');
        }

        return $this->fromPlace($home);
    }

    private function place(User $user, string $id): TripOrigin
    {
        $place = $this->places->findOwned($user, $id);
        if (null === $place) {
            throw new InvalidTripException('origin', "no place of yours has the id {$id}: list_places gives the ids.");
        }

        return $this->fromPlace($place);
    }

    private function fromPlace(Place $place): TripOrigin
    {
        [$latitude, $longitude] = $this->locations->ofPlace($place);

        return new TripOrigin(TripOrigin::PLACE, (string) $place->getId(), $place->isHome() ? 'la maison' : $place->getName(), $latitude, $longitude);
    }

    private function event(User $user, string $id): TripOrigin
    {
        $event = $this->events->find($id);
        if (!$event instanceof Event || (string) $event->getAgenda()->getUser()->getId() !== (string) $user->getId()) {
            throw new InvalidTripException('origin', "no event of yours has the id {$id}.");
        }

        [$latitude, $longitude] = $this->locations->ofEvent($event, 'origin');

        return new TripOrigin(TripOrigin::EVENT, $id, $event->getSummary(), $latitude, $longitude);
    }
}
