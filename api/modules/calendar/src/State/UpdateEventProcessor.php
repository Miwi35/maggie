<?php

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\UpdateEventCommand;
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
        /** @var Event|null $previous */
        $previous = $context['previous_data'] ?? null;

        // Only send fields that actually changed compared to previous state
        $envelope = $this->bus->dispatch(new UpdateEventCommand(
            eventId: (string) $data->getId(),
            summary: $previous === null || $data->getSummary() !== $previous->getSummary() ? $data->getSummary() : null,
            startAt: $previous === null || $data->getStartAt() != $previous->getStartAt() ? $data->getStartAt() : null,
            endAt: $previous === null || $data->getEndAt() != $previous->getEndAt() ? $data->getEndAt() : null,
            description: $previous === null || $data->getDescription() !== $previous->getDescription() ? $data->getDescription() : null,
            location: $previous === null || $data->getLocation() !== $previous->getLocation() ? $data->getLocation() : null,
            allDay: $previous === null || $data->isAllDay() !== $previous->isAllDay() ? $data->isAllDay() : null,
            rrule: $previous === null || $data->getRrule() !== $previous->getRrule() ? $data->getRrule() : null,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
