<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\TripDecider;
use Maggie\Calendar\Enum\TripLeg;
use Maggie\Calendar\Exception\InvalidTripException;
use Maggie\Calendar\Routing\RoutingException;
use Maggie\Calendar\UseCase\SetTrip;

/**
 * Computes a kept trip again, without the model (MAG-729): same mode, from the position — or from
 * the origin the user chose, kept. A `departAt` that moved by more than
 * {@see self::DEPART_TOLERANCE_MINUTES} is kept; a failed computation, or a trip that grew by more
 * than {@see self::DURATION_GROWTH_MINUTES}, is a choice for Maggie, since another mode may now be better.
 * A return keeps the `departAt` the user fixed (MAG-734): only its duration and arrival are recomputed.
 * A trip is computed on its occurrence, never on the start of its series (MAG-862).
 */
class TripRecheck
{
    public const int DEPART_TOLERANCE_MINUTES = 2;
    public const int DURATION_GROWTH_MINUTES = 15;

    public function __construct(
        private readonly TripPlanner $planner,
        private readonly SetTrip $setTrip,
        private readonly TripOccurrence $occurrences,
    ) {
    }

    public function recheck(Trip $trip): TripRecheckOutcome
    {
        $decidedBy = $trip->getOriginDecidedBy();

        try {
            $event = $this->occurrences->of($trip);
            $estimate = TripLeg::Return === $trip->getLeg()
                ? $this->planner->estimate($event, $trip->getMode(), TripOrigin::POSITION, null, TripLeg::Return, $trip->getDepartAt())
                : $this->planner->estimate($event, $trip->getMode(), $this->origin($trip), $trip->getArriveAt());
        } catch (InvalidTripException|RoutingException) {
            return TripRecheckOutcome::NeedsChoice;
        }

        if ($estimate->durationMinutes - $trip->getDurationMinutes() > self::DURATION_GROWTH_MINUTES) {
            return TripRecheckOutcome::NeedsChoice;
        }

        // A return leaves when the user said: it is its arrival that slides.
        $moved = TripLeg::Return === $trip->getLeg()
            ? abs(($estimate->arriveAt?->getTimestamp() ?? 0) - ($trip->getArriveAt()?->getTimestamp() ?? 0))
            : abs($estimate->departAt->getTimestamp() - $trip->getDepartAt()->getTimestamp());
        if ($moved <= self::DEPART_TOLERANCE_MINUTES * 60) {
            return TripRecheckOutcome::Unchanged;
        }

        $this->setTrip->keep($trip->getEvent(), TripOccurrence::startOf($event), $estimate, $decidedBy, $trip);

        return TripRecheckOutcome::Updated;
    }

    private function origin(Trip $trip): string
    {
        if (TripDecider::User !== $trip->getOriginDecidedBy() || TripOrigin::POSITION === $trip->getOriginKind() || null === $trip->getOriginRef()) {
            return TripOrigin::POSITION;
        }

        return $trip->getOriginKind().':'.$trip->getOriginRef();
    }
}
