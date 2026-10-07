<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Message\DeleteEventFromGoogleCommand;
use Maggie\Calendar\Message\PushEventToGoogleCommand;
use Maggie\Calendar\Message\UpdateEventCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\UseCase\UpdateEvent;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\TransportNamesStamp;

#[AsMessageHandler]
class UpdateEventHandler
{
    public function __construct(
        private readonly UpdateEvent $updateEvent,
        private readonly EventRepository $eventRepository,
        private readonly AgendaRepository $agendaRepository,
        private readonly MessageBusInterface $messageBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function __invoke(UpdateEventCommand $command): Event
    {
        $event = $this->eventRepository->find($command->eventId);
        if (null === $event) {
            throw new \DomainException("Event not found: {$command->eventId}");
        }

        if (null !== $command->summary) {
            $event->setSummary($command->summary);
        }
        if (null !== $command->description) {
            $event->setDescription($command->description);
        } elseif ($command->clears('description')) {
            $event->setDescription(null);
        }
        if (null !== $command->location) {
            $event->setLocation($command->location);
        } elseif ($command->clears('location')) {
            $event->setLocation(null);
        }
        if (null !== $command->status) {
            $status = EventStatus::tryFrom($command->status);
            if (null === $status) {
                throw new \DomainException("Invalid event status: {$command->status}");
            }
            $event->setStatus($status);
        }
        if (null !== $command->reminders) {
            $event->setReminders($command->reminders);
        } elseif ($command->clears('reminders')) {
            $event->setReminders(null);
        }

        // Through the API the managed entity already carries the new agenda, so the
        // processor names the one the event left.
        $fromAgenda = null !== $command->previousAgendaId
            ? $this->agendaRepository->find($command->previousAgendaId)
            : $event->getAgenda();
        $fromAgenda ??= $event->getAgenda();
        $fromGoogleEventId = $event->getGoogleEventId();
        $agendaChanged = false;
        if (null !== $command->agendaId && $fromAgenda->isModule()) {
            // What a module filed stays where it filed it — a meal moved to the
            // default agenda would be sent to Google (MAG-324). Through the API the
            // managed entity already carries the new agenda, so it is put back.
            $event->setAgenda($fromAgenda);
        } elseif (null !== $command->agendaId) {
            $agenda = $this->agendaRepository->find($command->agendaId);
            // A module's agenda is not one an ordinary event can be moved into (MAG-324).
            if (null === $agenda || $agenda->isModule() || (string) $agenda->getUser()->getId() !== (string) $fromAgenda->getUser()->getId()) {
                throw new \DomainException('No agenda found.');
            }
            $agendaChanged = (string) $agenda->getId() !== (string) $fromAgenda->getId();
            $event->setAgenda($agenda);
        }

        // A synced event leaving Google for a local-only agenda loses its Google copy
        $leavesGoogle = $agendaChanged && $fromAgenda->isGoogleSynced() && null !== $fromGoogleEventId && !$event->getAgenda()->isGoogleSynced();
        if ($leavesGoogle) {
            $event->setGoogleEventId(null);
            $event->setGoogleEtag(null);
            $event->setGoogleUpdatedAt(null);
        }

        if (null !== $command->startAt) {
            $event->setStartAt($command->startAt);
        }
        if (null !== $command->endAt) {
            $event->setEndAt($command->endAt);
        }
        if (null !== $command->allDay) {
            $event->setAllDay($command->allDay);
        }
        if (null !== $command->rrule) {
            $event->setRrule($command->rrule);
        } elseif ($command->clears('rrule')) {
            $event->setRrule(null);
        }

        // Track which fields were explicitly set in the command
        $changedFields = [];
        if (null !== $command->summary) {
            $changedFields[] = 'summary';
        }
        if (null !== $command->description || $command->clears('description')) {
            $changedFields[] = 'description';
        }
        if (null !== $command->location || $command->clears('location')) {
            $changedFields[] = 'location';
        }
        if (null !== $command->startAt) {
            $changedFields[] = 'startAt';
        }
        if (null !== $command->endAt) {
            $changedFields[] = 'endAt';
        }
        if (null !== $command->allDay) {
            $changedFields[] = 'allDay';
        }
        if (null !== $command->rrule || $command->clears('rrule')) {
            $changedFields[] = 'rrule';
        }
        if (null !== $command->status) {
            $changedFields[] = 'status';
        }
        if (null !== $command->reminders || $command->clears('reminders')) {
            $changedFields[] = 'reminders';
        }
        if ($agendaChanged) {
            $changedFields[] = 'agenda';
        }

        $event = $this->updateEvent->execute($event);

        if ($leavesGoogle) {
            $this->dispatchWithRetry(new DeleteEventFromGoogleCommand(
                agendaId: (string) $fromAgenda->getId(),
                googleEventId: $fromGoogleEventId,
            ));
        } elseif ($event->getAgenda()->isGoogleSynced()) {
            $moves = $agendaChanged && $fromAgenda->isGoogleSynced() && null !== $fromGoogleEventId;
            $this->dispatchWithRetry(new PushEventToGoogleCommand(
                eventId: (string) $event->getId(),
                action: $moves ? 'move' : ($agendaChanged && null === $fromGoogleEventId ? 'create' : 'update'),
                changedFields: $changedFields ?: null,
                fromGoogleCalendarId: $moves ? $fromAgenda->getGoogleCalendarId() : null,
            ));
        }

        return $event;
    }

    private function dispatchWithRetry(object $command): void
    {
        try {
            $this->messageBus->dispatch($command);
        } catch (\Throwable $e) {
            $this->logger->warning('Google sync failed, queuing retry: {error}', ['error' => $e->getMessage()]);
            $this->messageBus->dispatch($command, [new TransportNamesStamp(['async'])]);
        }
    }
}
