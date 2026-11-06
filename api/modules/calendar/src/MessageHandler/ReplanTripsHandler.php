<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\ReplanTripsCommand;
use Maggie\Calendar\Message\ScheduleProactionCommand;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Repository\ProactionRepository;
use Maggie\Calendar\Repository\TripRepository;
use Maggie\Calendar\Service\TripDay;
use Maggie\Calendar\Service\TripRecheck;
use Maggie\Calendar\Service\TripRecheckOutcome;
use Maggie\Core\Repository\UserRepository;
use Psr\Clock\ClockInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Plans the day's trips again (MAG-656, MAG-729). The API recomputes each kept trip itself
 * ({@see TripRecheck}, no model): a departure that slides is kept silently. Maggie is woken — a
 * proaction filed for now — only for what needs a choice: a trip that cannot be computed or grew
 * a lot, and an event of the day with a location and no trip. The prompt names them and says why.
 *
 * One at a time: while one is pending, a new trigger files nothing more. Once it has started, a
 * later trigger files another.
 */
#[AsMessageHandler]
final class ReplanTripsHandler
{
    public function __construct(
        private readonly UserRepository $users,
        private readonly ProactionRepository $proactions,
        private readonly TripRepository $trips,
        private readonly EventRepository $events,
        #[Autowire(lazy: true)]
        private readonly TripRecheck $recheck,
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(ReplanTripsCommand $command): void
    {
        $user = $this->users->find($command->userId);
        if (null === $user) {
            return;
        }

        $now = $this->clock->now();
        $zone = new \DateTimeZone(TripDay::TIME_ZONE);
        $today = $now->setTimezone($zone)->format('Y-m-d');
        [, $endOfDay] = TripDay::bounds($today);

        $lines = [];
        foreach ($this->trips->findLeavingBetween($user, $now, $endOfDay) as $trip) {
            if (TripRecheckOutcome::NeedsChoice === $this->recheck->recheck($trip)) {
                $lines[] = sprintf(
                    '- « %s » (id %s, trajet en %s) : le recalcul a échoué ou le trajet s\'est allongé de plus de %d minutes, un autre mode peut être meilleur.',
                    $trip->getEvent()->getSummary(),
                    $trip->getEvent()->getId(),
                    $trip->getMode()->value,
                    TripRecheck::DURATION_GROWTH_MINUTES,
                );
            }
        }
        if (\in_array($command->reason, [ReplanTripsCommand::EVENT_CREATED, ReplanTripsCommand::EVENT_LOCATED], true)) {
            foreach ($this->events->findLocatedWithoutTrip($user, $now, $endOfDay) as $event) {
                $lines[] = sprintf('- « %s » (id %s) : a un lieu et pas de trajet.', $event->getSummary(), $event->getId());
            }
        }

        if ([] === $lines || $this->proactions->hasPendingWithSummary($user, ReplanTripsCommand::SUMMARY)) {
            return;
        }

        $this->commandBus->dispatch(new ScheduleProactionCommand(
            userId: $command->userId,
            prompt: sprintf(
                "Replanifier les trajets du %s. %s\n"
                ."Les trajets qui tiennent encore ont déjà été recalculés par l'API. Reste à traiter, et seulement cela :\n%s",
                $today,
                self::reasonSentence($command->reason),
                implode("\n", $lines),
            ),
            scheduledAt: $now,
            summary: ReplanTripsCommand::SUMMARY,
        ));
    }

    private static function reasonSentence(string $reason): string
    {
        return match ($reason) {
            ReplanTripsCommand::EVENT_CREATED => 'Raison : un événement d\'aujourd\'hui avec un lieu vient d\'être créé.',
            ReplanTripsCommand::EVENT_LOCATED => 'Raison : un événement d\'aujourd\'hui vient d\'avoir un lieu.',
            ReplanTripsCommand::EVENT_UPDATED => 'Raison : l\'heure ou le lieu d\'un événement d\'aujourd\'hui vient de changer.',
            ReplanTripsCommand::EVENT_DELETED => 'Raison : un événement d\'aujourd\'hui vient d\'être supprimé.',
            ReplanTripsCommand::POSITION_MOVED => 'Raison : l\'utilisateur est ailleurs que là d\'où le prochain trajet a été calculé (plus de 300 m).',
            ReplanTripsCommand::CAR_MOVED => 'Raison : la voiture n\'est plus là où le prochain trajet l\'a prise en compte (plus de 300 m).',
            default => "Raison : {$reason}.",
        };
    }
}
