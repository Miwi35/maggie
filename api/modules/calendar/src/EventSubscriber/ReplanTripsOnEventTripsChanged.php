<?php

declare(strict_types=1);

namespace Maggie\Calendar\EventSubscriber;

use Maggie\Calendar\Event\EventTripsChangedEvent;
use Maggie\Calendar\Message\ReplanTripsCommand;
use Maggie\Calendar\Repository\TripRepository;
use Maggie\Calendar\Service\TripDay;
use Psr\Clock\ClockInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Turns the fact into a command; the rule lives in the command's handler.
 *
 * A removal or a change that plans no new trip only matters if the user has a trip today to
 * recompute: without one, waking the model is for nothing (MAG-726).
 */
#[AsMessageHandler(bus: 'event.bus')]
final class ReplanTripsOnEventTripsChanged
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
        private readonly TripRepository $trips,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(EventTripsChangedEvent $event): void
    {
        if (EventTripsChangedEvent::CREATED !== $event->change && !$event->locationAdded && !$this->hasTripToday($event->userId)) {
            return;
        }

        $reason = match ($event->change) {
            EventTripsChangedEvent::CREATED => ReplanTripsCommand::EVENT_CREATED,
            EventTripsChangedEvent::DELETED => ReplanTripsCommand::EVENT_DELETED,
            default => ReplanTripsCommand::EVENT_UPDATED,
        };

        $this->commandBus->dispatch(new ReplanTripsCommand($event->userId, $reason));
    }

    private function hasTripToday(string $userId): bool
    {
        $today = $this->clock->now()->setTimezone(new \DateTimeZone(TripDay::TIME_ZONE))->format('Y-m-d');
        [$from, $to] = TripDay::bounds($today);

        return $this->trips->hasLeavingBetween($userId, $from, $to);
    }
}
