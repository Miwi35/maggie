<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\TripDecider;
use Maggie\Calendar\Enum\TripLeg;
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

#[McpTool(name: 'set_trip', description: 'Set the trip to an event: recomputed now, replaces the event\'s trip for that leg. mode: drive, transit, walk, bicycle. origin: position (phone position), place:<id> or event:<id>. arriveAt: ISO 8601 Paris time, whole-day event only, refused otherwise. decidedBy: user (origin kept) or maggie (default). leg: outbound (default) or return (event place to home: no origin/arriveAt; departAt ISO 8601 Paris time is never moved; alert stops 30 min after). Returns {success, trip: {id, eventId, event: {summary, startAt, location}, leg, mode, arriveAt?, origin: {kind, id, label, latitude, longitude}, originDecidedBy, steps: [{kind, minutes, label?}], durationMinutes, departAt, navigationUrl, computedAt, carLatitude?, carLongitude?}}, Refused: nothing changes, {error, field}; Google down: {error}.')]
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
        ?string $origin = null,
        #[Schema(enum: ['user', 'maggie'])]
        ?string $decidedBy = null,
        ?string $arriveAt = null,
        #[Schema(enum: ['outbound', 'return'])]
        ?string $leg = null,
        ?string $departAt = null,
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

            $tripLeg = TripLeg::tryFrom($leg ?? TripLeg::Outbound->value)
                ?? throw new InvalidTripException('leg', "« {$leg} » is not a leg: outbound or return.");
            if (null === $origin && TripLeg::Outbound === $tripLeg) {
                throw new InvalidTripException('origin', 'send the origin of an outbound trip: position, place:<id> or event:<id>.');
            }

            try {
                $envelope = $this->bus->dispatch(new SetTripCommand((string) $user->getId(), $eventId, $tripMode, $origin ?? 'position', $decider, $arriveAt, $tripLeg, $departAt));
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
