<?php

namespace Maggie\Agenda\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Agenda\Entity\Event;
use Maggie\Agenda\Message\UpdateEventCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Event, Event> */
class UpdateEventProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Event
    {
        $envelope = $this->bus->dispatch(new UpdateEventCommand(
            eventId: (string) $data->getId(),
            summary: $data->getSummary(),
            startAt: $data->getStartAt(),
            endAt: $data->getEndAt(),
            description: $data->getDescription(),
            location: $data->getLocation(),
            allDay: $data->isAllDay(),
            rrule: $data->getRrule(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
