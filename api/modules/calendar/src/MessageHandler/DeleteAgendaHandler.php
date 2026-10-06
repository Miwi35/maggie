<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\DeleteAgendaCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Maggie\Calendar\UseCase\DeleteAgenda;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class DeleteAgendaHandler
{
    public function __construct(
        private readonly DeleteAgenda $deleteAgenda,
        private readonly AgendaRepository $agendaRepository,
        private readonly EventRepository $eventRepository,
        private readonly GoogleCalendarApiClient $googleApiClient,
        private readonly MessageBusInterface $messageBus,
        private readonly IndexMetadataReader $metadataReader,
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

        // The database cascade removes the agenda's events, meals included, without any command of their own.
        $documents = [];
        foreach ($this->eventRepository->findBy(['agenda' => $agenda]) as $event) {
            foreach ($this->metadataReader->indicesOf($event::class) as $indexName) {
                $documents[] = [$indexName, (string) $event->getId()];
            }
        }

        $this->deleteAgenda->execute($agenda);

        foreach ($documents as [$indexName, $documentId]) {
            $this->messageBus->dispatch(new DeleteDocumentCommand(indexName: $indexName, documentId: $documentId));
        }
    }
}
