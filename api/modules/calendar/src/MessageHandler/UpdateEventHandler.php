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
        if ($event === null) {
            throw new \DomainException("Event not found: {$command->eventId}");
        }

        if ($command->summary !== null) {
            $event->setSummary($command->summary);
        }
        if ($command->description !== null) {
            $event->setDescription($command->description);
        }
        if ($command->location !== null) {
            $event->setLocation($command->location);
        }
        if ($command->startAt !== null) {
            $event->setStartAt($command->startAt);
        }
        if ($command->endAt !== null) {
            $event->setEndAt($command->endAt);
        }
        if ($command->allDay !== null) {
            $event->setAllDay($command->allDay);
        }
        if ($command->rrule !== null) {
            $event->setRrule($command->rrule);
        }

        // Track which fields were explicitly set in the command
        $changedFields = [];
        if ($command->summary !== null) {
            $changedFields[] = 'summary';
        }
        if ($command->description !== null) {
            $changedFields[] = 'description';
        }
        if ($command->location !== null) {
            $changedFields[] = 'location';
        }
        if ($command->startAt !== null) {
            $changedFields[] = 'startAt';
        }
        if ($command->endAt !== null) {
            $changedFields[] = 'endAt';
        }
        if ($command->allDay !== null) {
            $changedFields[] = 'allDay';
        }
        if ($command->rrule !== null) {
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
