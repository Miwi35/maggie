<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\DeleteEventCommand;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\UseCase\DeleteEvent;
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
