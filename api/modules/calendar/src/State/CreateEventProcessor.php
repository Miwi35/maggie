<?php

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\CreateEventCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Event, Event> */
class CreateEventProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
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
            recurringEventId: null !== $data->getRecurringEvent() ? (string) $data->getRecurringEvent()->getId() : null,
            userId: $this->security->getUser() instanceof User ? (string) $this->security->getUser()->getId() : null,
            originalStartAt: $data->getOriginalStartAt(),
            status: $data->getStatus()->value,
            reminders: $data->getReminders(),
            startDate: $data->getStartDate(),
            endDate: $data->getEndDate(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
