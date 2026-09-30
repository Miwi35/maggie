<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\DeleteAgendaCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Maggie\Calendar\UseCase\DeleteAgenda;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteAgendaHandler
{
    public function __construct(
        private readonly DeleteAgenda $deleteAgenda,
        private readonly AgendaRepository $agendaRepository,
        private readonly GoogleCalendarApiClient $googleApiClient,
    ) {
    }

    public function __invoke(DeleteAgendaCommand $command): void
    {
        $agenda = $this->agendaRepository->find($command->agendaId);
        if (null === $agenda) {
            throw new \DomainException("Agenda not found: {$command->agendaId}");
        }

        // Stop Google webhook if active
        if (null !== $agenda->getGoogleWatchChannelId() && null !== $agenda->getGoogleWatchResourceId()) {
            try {
                $this->googleApiClient->stopWatch(
                    $agenda->getUser(),
                    $agenda->getGoogleWatchChannelId(),
                    $agenda->getGoogleWatchResourceId(),
                );
            } catch (\Throwable) {
                // Best effort
            }
        }

        // Delete Google Calendar if requested
        if ($command->deleteGoogleCalendar && null !== $agenda->getGoogleCalendarId()) {
            try {
                $this->googleApiClient->deleteCalendar(
                    $agenda->getUser(),
                    $agenda->getGoogleCalendarId(),
                );
            } catch (\Throwable) {
                // Best effort — calendar may have been deleted manually
            }
        }

        $this->deleteAgenda->execute($agenda);
    }
}
