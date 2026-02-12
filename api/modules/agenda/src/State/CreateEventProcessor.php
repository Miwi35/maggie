<?php

namespace Maggie\Agenda\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Agenda\Entity\Event;
use Maggie\Agenda\Message\CreateEventCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Event, Event> */
class CreateEventProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Event
    {
        $envelope = $this->bus->dispatch(new CreateEventCommand(
            summary: $data->getSummary(),
            startAt: $data->getStartAt(),
            endAt: $data->getEndAt(),
            calendarId: (string) $data->getCalendar()->getId(),
            description: $data->getDescription(),
            location: $data->getLocation(),
            timeZone: $data->getTimeZone(),
            allDay: $data->isAllDay(),
            rrule: $data->getRrule(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
