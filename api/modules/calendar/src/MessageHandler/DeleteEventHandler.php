<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\DeleteEventCommand;
use Maggie\Calendar\Message\DeleteEventFromGoogleCommand;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\UseCase\DeleteEvent;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Identifier\CanonicalId;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\TransportNamesStamp;

#[AsMessageHandler]
class DeleteEventHandler
{
    public function __construct(
        private readonly DeleteEvent $deleteEvent,
        private readonly EventRepository $eventRepository,
        private readonly MessageBusInterface $messageBus,
        private readonly LoggerInterface $logger,
        private readonly IndexMetadataReader $metadataReader,
    ) {
    }

    public function __invoke(DeleteEventCommand $command): void
    {
        $event = $this->eventRepository->find($command->eventId);
        if (null === $event) {
            throw new \DomainException("Event not found: {$command->eventId}");
        }

        // Capture Google info before deletion
        $googleEventId = $event->getGoogleEventId();
        $agendaId = (string) $event->getAgenda()->getId();
        $wasGoogleSynced = $event->isGoogleSynced();

        // The id may be a meal's: its document is also in the index of its own class, which the
        // command, named after Event, does not reach.
        $ownIndices = array_diff($this->metadataReader->indicesOf($event::class), $this->metadataReader->indicesOf(Event::class));

        // The database cascade removes the exception instances without any command of their own.
        $exceptionIds = array_map(
            fn ($exception) => (string) $exception->getId(),
            $this->eventRepository->findBy(['recurringEvent' => $event]),
        );

        $this->deleteEvent->execute($event);

        foreach ($ownIndices as $indexName) {
            $this->messageBus->dispatch(new DeleteDocumentCommand(indexName: $indexName, documentId: CanonicalId::of($command->eventId)));
        }

        foreach ($exceptionIds as $exceptionId) {
            $this->messageBus->dispatch(new DeleteDocumentCommand(indexName: 'events', documentId: $exceptionId));
        }

        if ($wasGoogleSynced && null !== $googleEventId) {
            $deleteCommand = new DeleteEventFromGoogleCommand(
                agendaId: $agendaId,
                googleEventId: $googleEventId,
            );
            try {
                $this->messageBus->dispatch($deleteCommand);
            } catch (\Throwable $e) {
                $this->logger->warning('Google sync failed, queuing retry: {error}', ['error' => $e->getMessage()]);
                $this->messageBus->dispatch($deleteCommand, [new TransportNamesStamp(['async'])]);
            }
        }
    }
}
