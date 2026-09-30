<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\DeleteEventCommand;
use Maggie\Calendar\Message\DeleteEventFromGoogleCommand;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\UseCase\DeleteEvent;
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

        $this->deleteEvent->execute($event);

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
