<?php

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\CreateEventCommand;
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
            agendaId: (string) $data->getAgenda()->getId(),
            description: $data->getDescription(),
            location: $data->getLocation(),
            timeZone: $data->getTimeZone(),
            allDay: $data->isAllDay(),
            rrule: $data->getRrule(),
            recurringEventId: (string) $data->getRecurringEvent()?->getId(),
            originalStartAt: $data->getOriginalStartAt(),
            status: $data->getStatus()->value,
            reminders: $data->getReminders(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
