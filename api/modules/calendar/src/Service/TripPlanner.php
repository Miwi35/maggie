<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Exception\InvalidTripException;
use Maggie\Calendar\Repository\PlaceRepository;
use Maggie\Calendar\Routing\Route;
use Maggie\Calendar\Routing\RouteLeg;
use Maggie\Calendar\Routing\RoutingClient;
use Maggie\Calendar\Routing\RoutingException;
use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserLocationRepository;
use Psr\Clock\ClockInterface;

/**
 * Door to door to an event (« Partir à l'heure », MAG-653).
 *
 * - **Car**: a walk to the car when it stands more than {@see self::CAR_WALK_METERS}
 *   away (where the phone saw it parked, home when that is unknown), the drive
 *   to the parking of the known place the event is at ({@see PlaceMatcher}),
 *   then the walk from the parking. Without a known parking, the drive goes to
 *   the event and {@see self::PARKING_MINUTES} are added to find a spot.
 * - **Transit**: Google's steps, walks included.
 * - **Walk**, **bicycle**: one step.
 *
 * `departAt` is the start, minus the trip, minus {@see self::MARGIN_MINUTES}, to the minute.
 */
class TripPlanner
{
    public const float CAR_WALK_METERS = 200.0;
    public const int PARKING_MINUTES = 5;
    public const int MARGIN_MINUTES = 5;

    /** The traffic asked for is that of this long before the start: about when one leaves. */
    private const int DRIVE_TRAFFIC_LEAD_MINUTES = 30;

    public function __construct(
        private readonly RoutingClient $routing,
        private readonly TripLocations $locations,
        private readonly TripOrigins $origins,
        private readonly PlaceMatcher $placeMatcher,
        private readonly PlaceRepository $places,
        private readonly UserLocationRepository $userLocations,
        private readonly ClockInterface $clock,
    ) {
    }

    /**
     * $origin is `position`, `place:<ulid>` or `event:<ulid>`, or one already
     * resolved — several modes from the same origin ask for the position once.
     *
     * @throws InvalidTripException
     * @throws RoutingException
     */
    public function estimate(Event $event, TripMode $mode, string|TripOrigin $origin): TripEstimate
    {
        $start = $event->getStartAt();
        if (null === $start) {
            throw new InvalidTripException('eventId', sprintf('« %s » lasts the whole day: there is no hour to arrive by.', $event->getSummary()));
        }

        $user = $event->getAgenda()->getUser();
        if (\is_string($origin)) {
            $origin = $this->origins->resolve($user, $origin);
        }
        [$latitude, $longitude] = $this->locations->ofEvent($event);

        $car = null;
        $parking = null;
        $routeMinutes = 0;
        switch ($mode) {
            case TripMode::Drive:
                ['steps' => $steps, 'car' => $car, 'parking' => $parking] = $this->drive($user, $origin, $latitude, $longitude, $start);
                break;
            case TripMode::Transit:
                $route = $this->transit($origin, $latitude, $longitude, $start);
                $steps = $route->legs;
                // The whole route, waits included, which its steps do not add up to.
                $routeMinutes = self::minutes($route->durationSeconds);
                break;
            default:
                $steps = $this->routing->route($mode, $origin->latitude, $origin->longitude, $latitude, $longitude)->legs;
        }

        $duration = max($routeMinutes, array_sum(array_map(static fn (RouteLeg $step): int => self::minutes($step->seconds), $steps)));

        $departAt = $start->setTimezone(self::zone($event))
            ->modify(sprintf('-%d minutes', $duration + self::MARGIN_MINUTES));
        $departAt = $departAt->setTime((int) $departAt->format('H'), (int) $departAt->format('i'));

        return new TripEstimate(
            mode: $mode,
            origin: $origin,
            steps: $steps,
            durationMinutes: $duration,
            departAt: $departAt,
            navigationUrl: self::navigationUrl($mode, $parking ?? [$latitude, $longitude]),
            parkingKnown: null !== $parking,
            carLatitude: $car[0] ?? null,
            carLongitude: $car[1] ?? null,
        );
    }

    /** Whole minutes, rounded up: a trip announced shorter than it is makes one late. */
    public static function minutes(int $seconds): int
    {
        return (int) ceil($seconds / 60);
    }

    /**
     * The steps, where the car is known to be and the parking driven to (null when unknown).
     *
     * @return array{steps: list<RouteLeg>, car: array{float, float}|null, parking: array{float, float}|null}
     */
    private function drive(User $user, TripOrigin $origin, float $latitude, float $longitude, \DateTimeImmutable $start): array
    {
        // A car nobody knows the place of is taken where the trip starts.
        $knownCar = $this->car($user);
        $car = $knownCar ?? [$origin->latitude, $origin->longitude];
        $steps = [];
        if (PlaceMatcher::distance($origin->latitude, $origin->longitude, $car[0], $car[1]) > self::CAR_WALK_METERS) {
            $steps[] = $this->leg(TripMode::Walk, [$origin->latitude, $origin->longitude], $car);
        }

        $this->locatePlaces($user);
        $place = $this->placeMatcher->match($user, $latitude, $longitude);
        $parking = null === $place ? null : $this->locations->ofParking($place);
        $leaveAround = $start->modify(sprintf('-%d minutes', self::DRIVE_TRAFFIC_LEAD_MINUTES));
        $departAt = $leaveAround > $this->clock->now() ? $leaveAround : null;

        if (null !== $parking) {
            $steps[] = $this->leg(TripMode::Drive, $car, $parking, $departAt);
            $steps[] = $this->leg(TripMode::Walk, $parking, [$latitude, $longitude]);

            return ['steps' => $steps, 'car' => $knownCar, 'parking' => $parking];
        }

        $drive = $this->leg(TripMode::Drive, $car, [$latitude, $longitude], $departAt);
        $steps[] = new RouteLeg(TripMode::Drive, $drive->seconds + self::PARKING_MINUTES * 60, sprintf('dont %d min pour se garer', self::PARKING_MINUTES));

        return ['steps' => $steps, 'car' => $knownCar, 'parking' => null];
    }

    private function transit(TripOrigin $origin, float $latitude, float $longitude, \DateTimeImmutable $start): Route
    {
        $arriveBy = $start->modify(sprintf('-%d minutes', self::MARGIN_MINUTES));

        return $this->routing->route(
            TripMode::Transit,
            $origin->latitude,
            $origin->longitude,
            $latitude,
            $longitude,
            arriveBy: $arriveBy > $this->clock->now() ? $arriveBy : null,
        );
    }

    /**
     * @param array{float, float} $from
     * @param array{float, float} $to
     */
    private function leg(TripMode $mode, array $from, array $to, ?\DateTimeImmutable $departAt = null): RouteLeg
    {
        $route = $this->routing->route($mode, $from[0], $from[1], $to[0], $to[1], $departAt);

        return new RouteLeg($mode, $route->durationSeconds);
    }

    /**
     * Geocodes the user's places saved without coordinates — save_place leaves
     * them out rather than guess — so the matcher can recognise the event's.
     * A place geocoded is kept; one Google cannot find, or cannot answer for,
     * stays unmatched and is tried again on the next drive.
     */
    private function locatePlaces(User $user): void
    {
        foreach ($this->places->findByUser($user) as $place) {
            if ($place->hasCoordinates()) {
                continue;
            }

            try {
                $this->locations->ofPlace($place, 'place');
            } catch (InvalidTripException|RoutingException) {
                // Only a candidate for the parking: the drive goes on without it.
                continue;
            }
        }
    }

    /**
     * Where the phone saw the car parked; home when it never did.
     *
     * @return array{float, float}|null
     */
    private function car(User $user): ?array
    {
        $location = $this->userLocations->findOneByUser($user);
        if (null !== $location?->getCarLatitude() && null !== $location->getCarLongitude()) {
            return [$location->getCarLatitude(), $location->getCarLongitude()];
        }

        $home = $this->places->findHomes($user)[0] ?? null;
        if (null === $home) {
            return null;
        }

        try {
            return $this->locations->ofPlace($home, 'home');
        } catch (InvalidTripException) {
            // A home Google cannot find leaves the car's place unknown, it refuses no trip.
            return null;
        }
    }

    /** @param array{float, float} $to */
    private static function navigationUrl(TripMode $mode, array $to): string
    {
        $point = $to[0].','.$to[1];

        return TripMode::Drive === $mode
            ? 'https://waze.com/ul?ll='.$point.'&navigate=yes'
            : 'https://www.google.com/maps/dir/?api=1&destination='.$point.'&travelmode='.$mode->mapsTravelMode();
    }

    private static function zone(Event $event): \DateTimeZone
    {
        try {
            return new \DateTimeZone($event->getTimeZone());
        } catch (\Exception) {
            return new \DateTimeZone(Event::FALLBACK_TIME_ZONE);
        }
    }
}
