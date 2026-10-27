<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\ReplanTripsCommand;
use Maggie\Calendar\Message\ScheduleProactionCommand;
use Maggie\Calendar\Repository\ProactionRepository;
use Maggie\Calendar\Service\TripDay;
use Maggie\Core\Repository\UserRepository;
use Psr\Clock\ClockInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Wakes Maggie to plan the day's trips again: a proaction filed for now (MAG-656). The API cannot
 * replan itself — the model chooses origin and mode — so it only says why.
 *
 * One at a time: while one is pending, a new trigger adds nothing, since the run that comes reads
 * the day as it then is. Once it has started, a later trigger files another.
 */
#[AsMessageHandler]
final class ReplanTripsHandler
{
    public function __construct(
        private readonly UserRepository $users,
        private readonly ProactionRepository $proactions,
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(ReplanTripsCommand $command): void
    {
        $user = $this->users->find($command->userId);
        if (null === $user || $this->proactions->hasPendingWithSummary($user, ReplanTripsCommand::SUMMARY)) {
            return;
        }

        $now = $this->clock->now();
        $today = $now->setTimezone(new \DateTimeZone(TripDay::TIME_ZONE))->format('Y-m-d');

        $this->commandBus->dispatch(new ScheduleProactionCommand(
            userId: $command->userId,
            prompt: sprintf(
                "Replanifier les trajets du %s. %s\n"
                .'Lis les trajets enregistrés du jour (list_trips) et les événements du jour avec un lieu, '
                .'puis recalcule (estimate_trip) et enregistre (set_trip) ceux qui ne tiennent plus. '
                .'Un trajet dont l\'origine a été choisie par l\'utilisateur garde son origine.',
                $today,
                self::reasonSentence($command->reason),
            ),
            scheduledAt: $now,
            summary: ReplanTripsCommand::SUMMARY,
        ));
    }

    private static function reasonSentence(string $reason): string
    {
        return match ($reason) {
            ReplanTripsCommand::EVENT_CREATED => 'Raison : un événement d\'aujourd\'hui avec un lieu vient d\'être créé.',
            ReplanTripsCommand::EVENT_UPDATED => 'Raison : l\'heure ou le lieu d\'un événement d\'aujourd\'hui vient de changer.',
            ReplanTripsCommand::EVENT_DELETED => 'Raison : un événement d\'aujourd\'hui vient d\'être supprimé.',
            ReplanTripsCommand::POSITION_MOVED => 'Raison : l\'utilisateur est ailleurs que là d\'où le prochain trajet a été calculé (plus de 300 m).',
            ReplanTripsCommand::CAR_MOVED => 'Raison : la voiture n\'est plus là où le prochain trajet l\'a prise en compte (plus de 300 m).',
            default => "Raison : {$reason}.",
        };
    }
}
