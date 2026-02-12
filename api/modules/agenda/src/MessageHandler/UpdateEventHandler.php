<?php

namespace Maggie\Agenda\MessageHandler;

use Maggie\Agenda\Entity\Event;
use Maggie\Agenda\Message\UpdateEventCommand;
use Maggie\Agenda\Repository\EventRepository;
use Maggie\Agenda\UseCase\UpdateEvent;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateEventHandler
{
    public function __construct(
        private readonly UpdateEvent $updateEvent,
        private readonly EventRepository $eventRepository,
    ) {
    }

    public function __invoke(UpdateEventCommand $command): Event
    {
        $event = $this->eventRepository->find($command->eventId);
        if ($event === null) {
            throw new \DomainException("Event not found: {$command->eventId}");
        }

        if ($command->summary !== null) {
            $event->setSummary($command->summary);
        }
        if ($command->description !== null) {
            $event->setDescription($command->description);
        }
        if ($command->location !== null) {
            $event->setLocation($command->location);
        }
        if ($command->startAt !== null) {
            $event->setStartAt($command->startAt);
        }
        if ($command->endAt !== null) {
            $event->setEndAt($command->endAt);
        }
        if ($command->allDay !== null) {
            $event->setAllDay($command->allDay);
        }
        if ($command->rrule !== null) {
            $event->setRrule($command->rrule);
        }

        return $this->updateEvent->execute($event);
    }
}
