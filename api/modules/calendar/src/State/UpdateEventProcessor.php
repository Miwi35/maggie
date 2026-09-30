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

        // A nullable field that was set and is now null is an explicit clear
        $clearFields = [];
        if (null !== $previous) {
            foreach (['description' => 'getDescription', 'location' => 'getLocation', 'rrule' => 'getRrule'] as $field => $getter) {
                if (null === $data->$getter() && null !== $previous->$getter()) {
                    $clearFields[] = $field;
                }
            }
        }

        // Only send fields that actually changed compared to previous state
        $envelope = $this->bus->dispatch(new UpdateEventCommand(
            eventId: (string) $data->getId(),
            summary: null === $previous || $data->getSummary() !== $previous->getSummary() ? $data->getSummary() : null,
            startAt: null === $previous || $data->getStartAt() != $previous->getStartAt() ? $data->getStartAt() : null,
            endAt: null === $previous || $data->getEndAt() != $previous->getEndAt() ? $data->getEndAt() : null,
            description: null === $previous || $data->getDescription() !== $previous->getDescription() ? $data->getDescription() : null,
            location: null === $previous || $data->getLocation() !== $previous->getLocation() ? $data->getLocation() : null,
            allDay: null === $previous || $data->isAllDay() !== $previous->isAllDay() ? $data->isAllDay() : null,
            rrule: null === $previous || $data->getRrule() !== $previous->getRrule() ? $data->getRrule() : null,
            clearFields: $clearFields,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
