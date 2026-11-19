<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\TripLeg;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Exception\InvalidTripException;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Routing\NoRouteException;
use Maggie\Calendar\Routing\RoutingException;
use Maggie\Calendar\Service\TripEstimate;
use Maggie\Calendar\Service\TripLocations;
use Maggie\Calendar\Service\TripOccurrence;
use Maggie\Calendar\Service\TripOrigin;
use Maggie\Calendar\Service\TripOrigins;
use Maggie\Calendar\Service\TripPlanner;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;
use Symfony\Component\Uid\Ulid;

#[McpTool(name: 'estimate_trip', description: 'Estimate the trip to an event, several modes at once. date (YYYY-MM-DD, Paris): the occurrence\'s day; required for a repeating event, else optional and must match. modes: drive, transit, walk, bicycle. origin: position (phone\'s last position, default), home (the user\'s home), place:<id> or event:<id>. leg: outbound (default) or return (event place to home, origin ignored, departAt required, ISO 8601 Paris time). arriveAt (ISO 8601 Paris time): required for a whole-day event, refused otherwise; departAt counts back from it. Returns {eventId, estimates: [{leg, mode, origin: {kind, id, label, latitude, longitude, capturedAt?}, steps: [{kind, minutes, label?}], durationMinutes, departAt, navigationUrl, parkingKnown, carLatitude?, carLongitude?}], unavailable: [{mode, reason}], locationRequested}. unavailable: modes with no route. locationRequested: position older than 10 min, used anyway. Refused: {error, field}; {error} alone if no route or Google unavailable.')]
final class EstimateTripTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly EventRepository $events,
        private readonly TripLocations $locations,
        private readonly TripOrigins $origins,
        private readonly TripPlanner $planner,
        private readonly TripOccurrence $occurrences,
    ) {
    }

    /** @param list<string> $modes */
    public function __invoke(
        string $eventId,
        #[Schema(items: ['type' => 'string', 'enum' => ['drive', 'transit', 'walk', 'bicycle']], minItems: 1)]
        array $modes,
        ?string $origin = null,
        ?string $arriveAt = null,
        #[Schema(enum: ['outbound', 'return'])]
        ?string $leg = null,
        ?string $departAt = null,
        ?string $date = null,
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
            $tripLeg = TripLeg::tryFrom($leg ?? TripLeg::Outbound->value)
                ?? throw new InvalidTripException('leg', "« {$leg} » is not a leg: outbound or return.");
            $arrival = null === $arriveAt ? null : TripPlanner::parseArriveAt($arriveAt);
            $departure = null === $departAt ? null : TripPlanner::parseArriveAt($departAt, 'departAt');
            $occurrence = $this->occurrences->on($event, $date);
            TripPlanner::checkHour($occurrence, $tripLeg, $arrival, $departure);
            // Where the event is before where the trip starts: the user fixes the event first.
            $this->locations->ofEvent($event);

            // Resolved once: several modes ask the phone for its position at most once. A return starts at the event.
            $from = $this->origins->resolve($user, TripLeg::Return === $tripLeg ? TripOrigin::EVENT.':'.$event->getId() : $origin ?? TripOrigin::POSITION);

            // A mode Google has no route for is reported apart; any other failure (Google down, no key) hits every mode and answers an error.
            $estimates = [];
            $unavailable = [];
            foreach ($tripModes as $mode) {
                try {
                    $estimates[] = $this->planner->estimate($occurrence, $mode, $from, $arrival, $tripLeg, $departure);
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
