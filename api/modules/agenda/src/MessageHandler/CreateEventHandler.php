<?php

namespace Maggie\Agenda\MessageHandler;

use Maggie\Agenda\Entity\Event;
use Maggie\Agenda\Message\CreateEventCommand;
use Maggie\Agenda\Repository\CalendarRepository;
use Maggie\Agenda\UseCase\CreateEvent;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateEventHandler
{
    public function __construct(
        private readonly CreateEvent $createEvent,
        private readonly CalendarRepository $calendarRepository,
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

        if ($command->description !== null) {
            $event->setDescription($command->description);
        }
        if ($command->location !== null) {
            $event->setLocation($command->location);
        }

        return $this->createEvent->execute($event);
    }
}
