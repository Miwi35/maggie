<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\EventStatus;
use Maggie\Calendar\Message\CreateEventCommand;
use Maggie\Calendar\Message\PushEventToGoogleCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\UseCase\CreateEvent;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class CreateEventHandler
{
    public function __construct(
        private readonly CreateEvent $createEvent,
        private readonly AgendaRepository $agendaRepository,
        private readonly EventRepository $eventRepository,
        private readonly MessageBusInterface $messageBus,
    ) {
    }

    public function __invoke(CreateEventCommand $command): Event
    {
        $agenda = $command->agendaId !== null
            ? $this->agendaRepository->find($command->agendaId)
            : $this->agendaRepository->findDefault();

        if ($agenda === null) {
            throw new \DomainException('No agenda found.');
        }

        $event = new Event();
        $event->setSummary($command->summary);
        $event->setStartAt($command->startAt);
        $event->setEndAt($command->endAt);
        $event->setTimeZone($command->timeZone);
        $event->setAgenda($agenda);
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

        $event = $this->createEvent->execute($event);

        if ($agenda->isGoogleSynced()) {
            $this->messageBus->dispatch(new PushEventToGoogleCommand(
                eventId: (string) $event->getId(),
                action: 'create',
            ));
        }

        return $event;
    }
}
