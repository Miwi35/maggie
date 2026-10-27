<?php

declare(strict_types=1);

namespace Maggie\Calendar\EventSubscriber;

use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Message\ReplanTripsCommand;
use Maggie\Calendar\Repository\TripRepository;
use Maggie\Calendar\Service\TripDay;
use Maggie\Calendar\Specification\IsUserAwayFromTrip;
use Maggie\Core\Event\UserLocationChangedEvent;
use Maggie\Core\Repository\UserLocationRepository;
use Maggie\Core\Repository\UserRepository;
use Psr\Clock\ClockInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * The position or the car moved (MAG-656): when the position is not where the next trip of the day was
 * computed from, or the car not where the next trip by car took it, Maggie plans the day's trips again. Nothing to replan without a trip left today.
 */
#[AsMessageHandler(bus: 'event.bus')]
final class ReplanTripsOnUserLocationChanged
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
        private readonly UserRepository $users,
        private readonly UserLocationRepository $locations,
        private readonly TripRepository $trips,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(UserLocationChangedEvent $event): void
    {
        $user = $this->users->find($event->userId);
        $location = null === $user ? null : $this->locations->findOneByUser($user);
        if (null === $user || null === $location) {
            return;
        }

        $now = $this->clock->now();
        [, $tomorrow] = TripDay::bounds($now->setTimezone(new \DateTimeZone(TripDay::TIME_ZONE))->format('Y-m-d'));
        $left = $this->trips->findLeavingBetween($user, $now, $tomorrow);
        $next = UserLocationChangedEvent::CAR === $event->what
            ? current(array_filter($left, static fn (Trip $trip): bool => TripMode::Drive === $trip->getMode())) ?: null
            : ($left[0] ?? null);
        if (null === $next || !(new IsUserAwayFromTrip($location, $event->what))->isSatisfiedBy($next)) {
            return;
        }

        $this->commandBus->dispatch(new ReplanTripsCommand(
            $event->userId,
            UserLocationChangedEvent::CAR === $event->what ? ReplanTripsCommand::CAR_MOVED : ReplanTripsCommand::POSITION_MOVED,
        ));
    }
}
