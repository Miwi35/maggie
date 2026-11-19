<?php

declare(strict_types=1);

namespace Maggie\Calendar\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\TripDecider;
use Maggie\Calendar\Enum\TripLeg;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Exception\InvalidTripException;
use Maggie\Calendar\Repository\TripRepository;
use Maggie\Calendar\Routing\RoutingException;
use Maggie\Calendar\Service\TripDay;
use Maggie\Calendar\Service\TripEstimate;
use Maggie\Calendar\Service\TripOccurrence;
use Maggie\Calendar\Service\TripPlanner;
use Psr\Clock\ClockInterface;

/**
 * The trip to an event, computed by {@see TripPlanner} and kept (MAG-654): an
 * event has one trip per leg and occurrence at most (MAG-862), a new one replaces it in
 * place — same id, same topic, the screens see an update.
 */
class SetTrip
{
    public function __construct(
        private readonly TripPlanner $planner,
        private readonly TripRepository $trips,
        private readonly EntityManagerInterface $em,
        private readonly ClockInterface $clock,
    ) {
    }

    /**
     * Plans `$occurrence` ({@see TripOccurrence::on()}) and keeps its trip on `$event`, the series it comes from.
     *
     * @throws InvalidTripException
     * @throws RoutingException
     */
    public function execute(Event $event, Event $occurrence, TripMode $mode, string $origin, TripDecider $decidedBy, ?\DateTimeImmutable $arriveAt = null, TripLeg $leg = TripLeg::Outbound, ?\DateTimeImmutable $departAt = null): Trip
    {
        return $this->keep(
            TripOccurrence::owner($event, $occurrence),
            TripOccurrence::startOf($occurrence),
            $this->planner->estimate($occurrence, $mode, $origin, $arriveAt, $leg, $departAt),
            $decidedBy,
        );
    }

    /**
     * Keeps an estimate already computed: the occurrence's trip is replaced in place, and follows its
     * occurrence when it moved — a single event has one, a series one a day. `$trip` is the one computed again.
     */
    public function keep(Event $event, \DateTimeImmutable $occurrenceStartAt, TripEstimate $estimate, TripDecider $decidedBy, ?Trip $trip = null): Trip
    {
        $trip ??= $this->occurrenceTrip($event, $estimate->leg, $occurrenceStartAt);
        if (null === $trip) {
            $trip = new Trip($event, $occurrenceStartAt);
            $this->em->persist($trip);
        }
        $trip->moveTo($event, $occurrenceStartAt);
        $trip->apply($estimate, $decidedBy, $this->clock->now());
        $this->em->flush();

        return $trip;
    }

    private function occurrenceTrip(Event $event, TripLeg $leg, \DateTimeImmutable $occurrenceStartAt): ?Trip
    {
        if (!$event->isRecurring()) {
            return $this->trips->findOneByEvent($event, $leg);
        }
        [$dayStart, $dayEnd] = TripDay::bounds($occurrenceStartAt->setTimezone(new \DateTimeZone(TripDay::TIME_ZONE))->format('Y-m-d'));

        return $this->trips->findOneByEventBetween($event, $dayStart, $dayEnd, $leg);
    }
}
