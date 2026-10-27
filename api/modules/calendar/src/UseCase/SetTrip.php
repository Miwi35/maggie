<?php

declare(strict_types=1);

namespace Maggie\Calendar\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\TripDecider;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Exception\InvalidTripException;
use Maggie\Calendar\Repository\TripRepository;
use Maggie\Calendar\Routing\RoutingException;
use Maggie\Calendar\Service\TripPlanner;
use Psr\Clock\ClockInterface;

/**
 * The trip to an event, computed by {@see TripPlanner} and kept (MAG-654): an
 * event has one trip at most, a new one replaces it in place — same id, same
 * topic, the screens see an update.
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
     * @throws InvalidTripException
     * @throws RoutingException
     */
    public function execute(Event $event, TripMode $mode, string $origin, TripDecider $decidedBy): Trip
    {
        $estimate = $this->planner->estimate($event, $mode, $origin);

        $trip = $this->trips->findOneByEvent($event);
        if (null === $trip) {
            $trip = new Trip($event);
            $this->em->persist($trip);
        }
        $trip->apply($estimate, $decidedBy, $this->clock->now());
        $this->em->flush();

        return $trip;
    }
}
