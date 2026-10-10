<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Message\CreateEventCommand;
use Maggie\Calendar\Message\PushEventToGoogleCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\UseCase\CreateEvent;
use Maggie\Core\Repository\UserRepository;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\TransportNamesStamp;

#[AsMessageHandler]
class CreateEventHandler
{
    public function __construct(
        private readonly CreateEvent $createEvent,
        private readonly AgendaRepository $agendaRepository,
        private readonly EventRepository $eventRepository,
        private readonly UserRepository $userRepository,
        private readonly MessageBusInterface $messageBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function __invoke(CreateEventCommand $command): Event
    {
        $agenda = null !== $command->agendaId
            ? $this->agendaRepository->find($command->agendaId)
            : $this->findDefaultAgenda($command->userId);

        if (null === $agenda && null === $command->agendaId) {
            throw new \DomainException('No default agenda is set: ask which agenda to use, or pass agenda_id (ids come from manage_agendas with action list).');
        }

        // An agenda a module keeps for itself holds what that module files in it, nothing else (MAG-324).
        if (null === $agenda || $agenda->isModule() || (null !== $command->userId && (string) $agenda->getUser()->getId() !== $command->userId)) {
            throw new \DomainException('No agenda found.');
        }

        $event = new Event();
        $event->setSummary($command->summary);
        $event->setTimeZone($command->timeZone);
        $event->setAgenda($agenda);
        if ($command->allDay) {
            if (null === $command->startDate) {
                throw new \DomainException('An all-day event needs a startDate.');
            }
            $event->scheduleAllDay($command->startDate, $command->endDate);
        } else {
            if (null === $command->startAt || null === $command->endAt) {
                throw new \DomainException('A timed event needs a startAt and an endAt.');
            }
            $event->scheduleTimed($command->startAt, $command->endAt);
        }

        if (null !== $command->description) {
            $event->setDescription($command->description);
        }
        if (null !== $command->location) {
            $event->setLocation($command->location);
        }
        if (null !== $command->rrule) {
            $event->setRrule($command->rrule);
        }
        if (null !== $command->recurringEventId) {
            $recurringEvent = $this->eventRepository->find($command->recurringEventId);
            if (null !== $recurringEvent) {
                $event->setRecurringEvent($recurringEvent);
            }
        }
        if (null !== $command->originalStartAt) {
            $event->setOriginalStartAt($command->originalStartAt);
        }
        if (null !== $command->status) {
            $event->setStatus(EventStatus::from($command->status));
        }
        if (null !== $command->reminders) {
            $event->setReminders($command->reminders);
        }

        $event = $this->createEvent->execute($event);

        if ($agenda->isGoogleSynced()) {
            $pushCommand = new PushEventToGoogleCommand(
                eventId: (string) $event->getId(),
                action: 'create',
            );
            try {
                $this->messageBus->dispatch($pushCommand);
            } catch (\Throwable $e) {
                $this->logger->warning('Google sync failed, queuing retry: {error}', ['error' => $e->getMessage()]);
                $this->messageBus->dispatch($pushCommand, [new TransportNamesStamp(['async'])]);
            }
        }

        return $event;
    }

    private function findDefaultAgenda(?string $userId): ?Agenda
    {
        $user = null !== $userId ? $this->userRepository->find($userId) : null;

        return null !== $user ? $this->agendaRepository->findDefault($user) : null;
    }
}
