<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Exception\InvalidTripException;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Routing\NoRouteException;
use Maggie\Calendar\Routing\RoutingException;
use Maggie\Calendar\Service\TripEstimate;
use Maggie\Calendar\Service\TripLocations;
use Maggie\Calendar\Service\TripOrigin;
use Maggie\Calendar\Service\TripOrigins;
use Maggie\Calendar\Service\TripPlanner;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;
use Symfony\Component\Uid\Ulid;

#[McpTool(name: 'estimate_trip', description: 'Estimate the trip to an event, door to door, for one or several modes in one call — compare them before choosing. modes: drive, transit, walk, bicycle. origin: position (where the phone last was, the default), place:<id> (list_places gives the ids, home included) or event:<id> (where an earlier event is). Returns {eventId, estimates: [{mode, origin: {kind, id, label, latitude, longitude, capturedAt?}, steps: [{kind, minutes, label?}], durationMinutes, departAt, navigationUrl, parkingKnown, carLatitude?, carLongitude?}], unavailable: [{mode, reason}], locationRequested}. unavailable lists the modes Google has no route for (no transit line there, say): the other modes are still estimated, so do not retry them. departAt is when to leave: the start minus the trip minus 5 minutes. By car the trip walks to the car when it is more than 200 m away, drives to the parking of the known place and walks from it; parkingKnown false means no parking is known and 5 minutes are counted to find a spot. locationRequested true means the position was older than 10 minutes: the phone was asked for a new one and the estimate uses the old one. The event needs a location. A refused call answers {error, field}; {error} alone when no mode has a route, or when Google is unreachable or not configured.')]
final class EstimateTripTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly EventRepository $events,
        private readonly TripLocations $locations,
        private readonly TripOrigins $origins,
        private readonly TripPlanner $planner,
    ) {
    }

    /** @param list<string> $modes */
    public function __invoke(
        string $eventId,
        #[Schema(items: ['type' => 'string', 'enum' => ['drive', 'transit', 'walk', 'bicycle']], minItems: 1)]
        array $modes,
        ?string $origin = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        }

        try {
            $tripModes = self::modes($modes);

            $event = Ulid::isValid($eventId) ? $this->events->find($eventId) : null;
            // Another user's event answers exactly like a missing one.
            if (!$event instanceof Event || (string) $event->getAgenda()->getUser()->getId() !== (string) $user->getId()) {
                throw new InvalidTripException('eventId', "no event of yours has the id {$eventId}.");
            }
            if (null === $event->getStartAt()) {
                throw new InvalidTripException('eventId', sprintf('« %s » lasts the whole day: there is no hour to arrive by.', $event->getSummary()));
            }
            // Where the event is before where the trip starts: the user fixes the event first.
            $this->locations->ofEvent($event);

            // Resolved once: several modes ask the phone for its position at most once.
            $from = $this->origins->resolve($user, $origin ?? TripOrigin::POSITION);

            // A mode Google has no route for is reported apart; any other failure (Google down, no key) hits every mode and answers an error.
            $estimates = [];
            $unavailable = [];
            foreach ($tripModes as $mode) {
                try {
                    $estimates[] = $this->planner->estimate($event, $mode, $from);
                } catch (NoRouteException $e) {
                    $unavailable[] = ['mode' => $mode->value, 'reason' => $e->getMessage()];
                }
            }
            if ([] === $estimates) {
                throw new RoutingException(implode(' ', array_column($unavailable, 'reason')));
            }
        } catch (InvalidTripException $e) {
            return self::json(['error' => $e->getMessage(), 'field' => $e->field]);
        } catch (RoutingException $e) {
            return self::json(['error' => 'The trip cannot be estimated: '.$e->getMessage()]);
        }

        return self::json([
            'eventId' => (string) $event->getId(),
            'estimates' => array_map(static fn (TripEstimate $estimate): array => $estimate->toArray(), $estimates),
            'unavailable' => $unavailable,
            'locationRequested' => $from->locationRequested,
        ]);
    }

    /**
     * @param array<mixed> $modes
     *
     * @return list<TripMode>
     */
    private static function modes(array $modes): array
    {
        if ([] === $modes) {
            throw new InvalidTripException('modes', 'send at least one mode: drive, transit, walk or bicycle.');
        }

        $tripModes = [];
        foreach ($modes as $mode) {
            $tripMode = \is_string($mode) ? TripMode::tryFrom($mode) : null;
            if (null === $tripMode) {
                throw new InvalidTripException('modes', sprintf('« %s » is not a mode: drive, transit, walk or bicycle.', \is_scalar($mode) ? $mode : get_debug_type($mode)));
            }
            $tripModes[$tripMode->value] = $tripMode;
        }

        return array_values($tripModes);
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, JSON_THROW_ON_ERROR);
    }
}
