<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\TripDecider;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Exception\InvalidTripException;
use Maggie\Calendar\Mcp\TripJson;
use Maggie\Calendar\Message\SetTripCommand;
use Maggie\Calendar\Routing\RoutingException;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'set_trip', description: 'Keep the trip the user will take to an event, once you chose the mode and the origin (estimate_trip compares them first). The trip is computed again now and replaces the one the event had: an event has one trip at most, and the departure alert follows its departAt. mode: drive, transit, walk or bicycle. origin: position (where the phone last was), place:<id> (list_places gives the ids, home included) or event:<id> (where an earlier event is). decidedBy: user when the user chose the origin themselves (an answer the evening before) — it is then kept and only the durations are computed again; maggie (the default) when you chose it. Returns {success, trip: {id, eventId, event: {summary, startAt, location}, mode, origin: {kind, id, label, latitude, longitude}, originDecidedBy, steps: [{kind, minutes, label?}], durationMinutes, departAt, navigationUrl, computedAt, carLatitude?, carLongitude?}}, times in Paris. A refused call changes nothing and answers {error, field}; Google unreachable or not configured answers {error}.')]
final class SetTripTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(
        string $eventId,
        #[Schema(enum: ['drive', 'transit', 'walk', 'bicycle'])]
        string $mode,
        string $origin,
        #[Schema(enum: ['user', 'maggie'])]
        ?string $decidedBy = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        }

        try {
            $tripMode = TripMode::tryFrom($mode)
                ?? throw new InvalidTripException('mode', "« {$mode} » is not a mode: drive, transit, walk or bicycle.");
            $decider = TripDecider::tryFrom($decidedBy ?? TripDecider::Maggie->value)
                ?? throw new InvalidTripException('decidedBy', "« {$decidedBy} » is not who decided: user or maggie.");

            try {
                $envelope = $this->bus->dispatch(new SetTripCommand((string) $user->getId(), $eventId, $tripMode, $origin, $decider));
            } catch (HandlerFailedException $e) {
                // A refusal or Google failing is Maggie's to read; anything else is ours.
                $cause = $e->getPrevious() ?? $e;
                if (!$cause instanceof InvalidTripException && !$cause instanceof RoutingException) {
                    throw $e;
                }
                throw $cause;
            }
        } catch (InvalidTripException $e) {
            return self::json(['error' => $e->getMessage(), 'field' => $e->field]);
        } catch (RoutingException $e) {
            return self::json(['error' => 'The trip cannot be computed: '.$e->getMessage()]);
        }

        /** @var Trip $trip */
        $trip = $envelope->last(HandledStamp::class)?->getResult();

        return self::json(['success' => true, 'trip' => TripJson::of($trip)]);
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, JSON_THROW_ON_ERROR);
    }
}
