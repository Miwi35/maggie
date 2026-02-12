<?php

namespace Maggie\Agenda\MessageHandler;

use Maggie\Agenda\Message\DeleteEventCommand;
use Maggie\Agenda\Repository\EventRepository;
use Maggie\Agenda\UseCase\DeleteEvent;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteEventHandler
{
    public function __construct(
        private readonly DeleteEvent $deleteEvent,
        private readonly EventRepository $eventRepository,
    ) {
    }

    public function __invoke(DeleteEventCommand $command): void
    {
        $event = $this->eventRepository->find($command->eventId);
        if ($event === null) {
            throw new \DomainException("Event not found: {$command->eventId}");
        }

        $this->deleteEvent->execute($event);
    }
}
