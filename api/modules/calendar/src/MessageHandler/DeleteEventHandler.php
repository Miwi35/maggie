<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\DeleteEventCommand;
use Maggie\Calendar\Message\DeleteEventFromGoogleCommand;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\UseCase\DeleteEvent;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class DeleteEventHandler
{
    public function __construct(
        private readonly DeleteEvent $deleteEvent,
        private readonly EventRepository $eventRepository,
        private readonly MessageBusInterface $messageBus,
    ) {
    }

    public function __invoke(DeleteEventCommand $command): void
    {
        $event = $this->eventRepository->find($command->eventId);
        if ($event === null) {
            throw new \DomainException("Event not found: {$command->eventId}");
        }

        // Capture Google info before deletion
        $googleEventId = $event->getGoogleEventId();
        $agendaId = (string) $event->getAgenda()->getId();
        $wasGoogleSynced = $event->isGoogleSynced();

        $this->deleteEvent->execute($event);

        if ($wasGoogleSynced && $googleEventId !== null) {
            $this->messageBus->dispatch(new DeleteEventFromGoogleCommand(
                agendaId: $agendaId,
                googleEventId: $googleEventId,
            ));
        }
    }
}
