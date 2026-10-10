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
            foreach (['description' => 'getDescription', 'location' => 'getLocation', 'rrule' => 'getRrule', 'reminders' => 'getReminders'] as $field => $getter) {
                if (null === $data->$getter() && null !== $previous->$getter()) {
                    $clearFields[] = $field;
                }
            }
        }

        // The schedule travels whole when any part of it moved: the handler sets
        // one kind or the other, never half of each (MAG-382).
        $scheduleChanged = null === $previous
            || $data->isAllDay() !== $previous->isAllDay()
            || $data->getStartAt() != $previous->getStartAt()
            || $data->getEndAt() != $previous->getEndAt()
            || $data->getStartDate() != $previous->getStartDate()
            || $data->getEndDate() != $previous->getEndDate();

        $agendaChanged = null === $previous || $data->getAgenda()->getId()->toString() !== $previous->getAgenda()->getId()->toString();

        // Only send fields that actually changed compared to previous state
        $envelope = $this->bus->dispatch(new UpdateEventCommand(
            eventId: (string) $data->getId(),
            summary: null === $previous || $data->getSummary() !== $previous->getSummary() ? $data->getSummary() : null,
            startAt: $scheduleChanged ? $data->getStartAt() : null,
            endAt: $scheduleChanged ? $data->getEndAt() : null,
            description: null === $previous || $data->getDescription() !== $previous->getDescription() ? $data->getDescription() : null,
            location: null === $previous || $data->getLocation() !== $previous->getLocation() ? $data->getLocation() : null,
            allDay: $scheduleChanged ? $data->isAllDay() : null,
            rrule: null === $previous || $data->getRrule() !== $previous->getRrule() ? $data->getRrule() : null,
            status: null === $previous || $data->getStatus() !== $previous->getStatus() ? $data->getStatus()->value : null,
            agendaId: $agendaChanged ? (string) $data->getAgenda()->getId() : null,
            previousAgendaId: $agendaChanged && null !== $previous ? (string) $previous->getAgenda()->getId() : null,
            reminders: null === $previous || $data->getReminders() != $previous->getReminders() ? $data->getReminders() : null,
            clearFields: $clearFields,
            startDate: $scheduleChanged ? $data->getStartDate() : null,
            endDate: $scheduleChanged ? $data->getEndDate() : null,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
