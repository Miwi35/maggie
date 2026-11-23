<?php

namespace Maggie\Calendar\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Enum\ProactionStatus;
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
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(DeleteEventCommand $command): void
    {
        $event = $this->eventRepository->find($command->eventId);
        if (null === $event) {
            throw new \DomainException("Event not found: {$command->eventId}");
        }

        // An occurrence of a proaction series is cancelled, never erased: its row is the
        // trace that it was not run (ADR-012-1).
        if ($event instanceof Proaction && $event->isException()) {
            $event->setStatus(EventStatus::Cancelled);
            $this->em->flush();

            return;
        }
        if ($event instanceof Proaction && $event->isRecurring()) {
            $this->deleteSeriesRuns($event);
        }

        // Capture Google info before deletion
        $googleEventId = $event->getGoogleEventId();
        $agendaId = (string) $event->getAgenda()->getId();
        $wasGoogleSynced = $event->isGoogleSynced();

        // The id may be a meal's: its document is also in the index of its own class, which the
        // command, named after Event, does not reach.
        $ownIndices = array_diff($this->metadataReader->indicesOf($event::class), $this->metadataReader->indicesOf(Event::class));

        $this->deleteEvent->execute($event);

        foreach ($ownIndices as $indexName) {
            $this->messageBus->dispatch(new DeleteDocumentCommand(indexName: $indexName, documentId: CanonicalId::of($command->eventId)));
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

    /** The runs to come leave with the series; the ones that happened stay, detached from it. */
    private function deleteSeriesRuns(Proaction $series): void
    {
        $indices = $this->metadataReader->indicesOf(Proaction::class);
        $gone = [];
        foreach ($this->eventRepository->findExceptionsForRecurringEvent($series) as $run) {
            if ($run instanceof Proaction && ProactionStatus::Pending !== $run->getExecutionStatus()) {
                $run->setRecurringEvent(null);
                continue;
            }
            $gone[] = $run;
        }
        $this->em->flush();

        foreach ($gone as $run) {
            $id = (string) $run->getId();
            $this->deleteEvent->execute($run);
            foreach ($indices as $indexName) {
                $this->messageBus->dispatch(new DeleteDocumentCommand(indexName: $indexName, documentId: CanonicalId::of($id)));
            }
        }
    }
}
