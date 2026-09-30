<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\PushEventToGoogleCommand;
use Maggie\Calendar\Message\UpdateEventCommand;
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

        $event = $this->updateEvent->execute($event);

        if ($event->getAgenda()->isGoogleSynced()) {
            $pushCommand = new PushEventToGoogleCommand(
                eventId: (string) $event->getId(),
                action: 'update',
                changedFields: $changedFields ?: null,
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
}
