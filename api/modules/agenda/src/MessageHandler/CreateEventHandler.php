<?php

namespace Maggie\Agenda\MessageHandler;

use Maggie\Agenda\Entity\Event;
use Maggie\Agenda\Entity\EventStatus;
use Maggie\Agenda\Message\CreateEventCommand;
use Maggie\Agenda\Repository\CalendarRepository;
use Maggie\Agenda\Repository\EventRepository;
use Maggie\Agenda\UseCase\CreateEvent;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateEventHandler
{
    public function __construct(
        private readonly CreateEvent $createEvent,
        private readonly CalendarRepository $calendarRepository,
        private readonly EventRepository $eventRepository,
    ) {
    }

    public function __invoke(CreateEventCommand $command): Event
    {
        $calendar = $command->calendarId !== null
            ? $this->calendarRepository->find($command->calendarId)
            : $this->calendarRepository->findDefault();

        if ($calendar === null) {
            throw new \DomainException('No calendar found.');
        }

        $event = new Event();
        $event->setSummary($command->summary);
        $event->setStartAt($command->startAt);
        $event->setEndAt($command->endAt);
        $event->setTimeZone($command->timeZone);
        $event->setCalendar($calendar);
        $event->setAllDay($command->allDay);

        if ($command->description !== null) {
            $event->setDescription($command->description);
        }
        if ($command->location !== null) {
            $event->setLocation($command->location);
        }
        if ($command->rrule !== null) {
            $event->setRrule($command->rrule);
        }
        if ($command->recurringEventId !== null) {
            $recurringEvent = $this->eventRepository->find($command->recurringEventId);
            if ($recurringEvent !== null) {
                $event->setRecurringEvent($recurringEvent);
            }
        }
        if ($command->originalStartAt !== null) {
            $event->setOriginalStartAt($command->originalStartAt);
        }
        if ($command->status !== null) {
            $event->setStatus(EventStatus::from($command->status));
        }

        return $this->createEvent->execute($event);
    }
}
