<?php

namespace Maggie\Calendar\Service;

use Doctrine\ORM\EntityManagerInterface;
use Google\Service\Exception as GoogleServiceException;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\UpdateAgendaCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Entity\User;
use Psr\Log\LoggerInterface;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;
use Symfony\Component\Messenger\MessageBusInterface;

class GoogleCalendarSyncService
{
    public function __construct(
        private readonly GoogleCalendarApiClient $apiClient,
        private readonly GoogleEventMapper $eventMapper,
        private readonly GoogleCalendarNameMapper $nameMapper,
        private readonly EventRepository $eventRepository,
        private readonly AgendaRepository $agendaRepository,
        private readonly EntityManagerInterface $entityManager,
        private readonly HubInterface $hub,
        private readonly MessageBusInterface $messageBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function pullFromGoogle(Agenda $agenda): void
    {
        if (!$agenda->isGoogleSynced()) {
            return;
        }

        $user = $agenda->getUser();
        $calendarId = $agenda->getGoogleCalendarId();
        $syncToken = $agenda->getGoogleSyncToken();

        $this->syncAgendaFromGoogle($user, $agenda, $calendarId);

        try {
            $this->doPull($user, $agenda, $calendarId, $syncToken);
        } catch (GoogleServiceException $e) {
            if (410 === $e->getCode()) {
                // Sync token expired, do a full sync
                $this->logger->info('Sync token expired for agenda {agenda}, doing full sync', [
                    'agenda' => $agenda->getId(),
                ]);
                $agenda->setGoogleSyncToken(null);
                $this->entityManager->flush();
                $this->fullSync($agenda);
            } else {
                throw $e;
            }
        }
    }

    public function fullSync(Agenda $agenda): void
    {
        if (!$agenda->isGoogleSynced()) {
            return;
        }

        $this->doPull($agenda->getUser(), $agenda, $agenda->getGoogleCalendarId(), null);
    }

    /**
     * @param string[]|null $changedFields        Fields that changed (null = full update)
     * @param string|null   $fromGoogleCalendarId With action "move", the Google calendar the event leaves
     */
    public function pushEventToGoogle(Event $event, string $action, ?array $changedFields = null, ?string $fromGoogleCalendarId = null): void
    {
        $agenda = $event->getAgenda();
        if (!$agenda->isGoogleSynced()) {
            return;
        }

        $user = $agenda->getUser();
        $calendarId = $agenda->getGoogleCalendarId();

        try {
            $googleEventId = $event->getGoogleEventId();
            if ('create' === $action || null === $googleEventId) {
                $googleEvent = $this->eventMapper->toGoogle($event);
                $result = $this->apiClient->insertEvent($user, $calendarId, $googleEvent);
                $event->setGoogleEventId($result->getId());
                $event->setGoogleEtag($result->getEtag());
                /** @var ?string $updatedAt */
                $updatedAt = $result->getUpdated();
                if ($updatedAt) {
                    $event->setGoogleUpdatedAt(new \DateTimeImmutable($updatedAt));
                }
            } elseif ('move' === $action && null !== $fromGoogleCalendarId) {
                $result = $this->apiClient->moveEvent($user, $fromGoogleCalendarId, $googleEventId, $calendarId);
                $this->trackGoogleResult($event, $result);

                $otherFields = array_values(array_diff($changedFields ?? [], ['agenda']));
                if ([] !== $otherFields) {
                    $result = $this->apiClient->patchEvent($user, $calendarId, $googleEventId, $this->eventMapper->toGooglePatch($event, $otherFields));
                    $this->trackGoogleResult($event, $result);
                }
            } elseif (null !== $changedFields && [] !== $changedFields) {
                $googleEvent = $this->eventMapper->toGooglePatch($event, $changedFields);
                $result = $this->apiClient->patchEvent($user, $calendarId, $googleEventId, $googleEvent);
                $event->setGoogleEtag($result->getEtag());
                /** @var ?string $updatedAt */
                $updatedAt = $result->getUpdated();
                if ($updatedAt) {
                    $event->setGoogleUpdatedAt(new \DateTimeImmutable($updatedAt));
                }
            } else {
                $googleEvent = $this->eventMapper->toGoogle($event);
                $result = $this->apiClient->updateEvent($user, $calendarId, $googleEventId, $googleEvent);
                $event->setGoogleEtag($result->getEtag());
                /** @var ?string $updatedAt */
                $updatedAt = $result->getUpdated();
                if ($updatedAt) {
                    $event->setGoogleUpdatedAt(new \DateTimeImmutable($updatedAt));
                }
            }

            $this->entityManager->flush();
        } catch (GoogleServiceException $e) {
            $this->logger->error('Failed to push event to Google: {error}', [
                'error' => $e->getMessage(),
                'eventId' => (string) $event->getId(),
                'action' => $action,
            ]);
            throw $e;
        }
    }

    private function trackGoogleResult(Event $event, \Google\Service\Calendar\Event $result): void
    {
        $event->setGoogleEtag($result->getEtag());
        /** @var ?string $updatedAt */
        $updatedAt = $result->getUpdated();
        if ($updatedAt) {
            $event->setGoogleUpdatedAt(new \DateTimeImmutable($updatedAt));
        }
    }

    public function deleteEventFromGoogle(Agenda $agenda, string $googleEventId): void
    {
        if (!$agenda->isGoogleSynced()) {
            return;
        }

        try {
            $this->apiClient->deleteEvent($agenda->getUser(), $agenda->getGoogleCalendarId(), $googleEventId);
        } catch (GoogleServiceException $e) {
            if (404 === $e->getCode() || 410 === $e->getCode()) {
                // Already deleted on Google side, ignore
                $this->logger->info('Event already deleted on Google: {googleEventId}', [
                    'googleEventId' => $googleEventId,
                ]);

                return;
            }
            throw $e;
        }
    }

    public function syncAllAgendasForUser(User $user): void
    {
        $agendas = $this->agendaRepository->findGoogleSyncedByUser($user);

        foreach ($agendas as $agenda) {
            $this->pullFromGoogle($agenda);
        }
    }

    /**
     * Google owns the name and the colour of a connected agenda (MAG-148).
     *
     * Both applications are then showing the calendar the way Google does.
     * It goes through the bus rather than a bare flush, so a rename reaches
     * the open admin and mobile screens and the search index — the agenda
     * collection is served from Elasticsearch, and a direct flush left it
     * answering with the old name.
     */
    private function syncAgendaFromGoogle(User $user, Agenda $agenda, string $calendarId): void
    {
        try {
            $calendarEntry = $this->apiClient->getCalendarListEntry($user, $calendarId);
            $googleName = $this->nameMapper->nameFor($calendarEntry);
            /** @var ?string $googleColor a shared calendar may carry no colour */
            $googleColor = $calendarEntry->getBackgroundColor();

            $name = $googleName === $agenda->getName() ? null : $googleName;
            $color = (null === $googleColor || $googleColor === $agenda->getColor()) ? null : $googleColor;

            if (null === $name && null === $color) {
                return;
            }

            $this->messageBus->dispatch(new UpdateAgendaCommand(
                agendaId: (string) $agenda->getId(),
                name: $name,
                color: $color,
            ));
        } catch (\Throwable $e) {
            $this->logger->warning('Failed to sync the agenda from Google: {error}', [
                'error' => $e->getMessage(),
                'agenda' => (string) $agenda->getId(),
            ]);
        }
    }

    private function doPull(User $user, Agenda $agenda, string $calendarId, ?string $syncToken): void
    {
        $pageToken = null;
        $changedEventIds = [];
        $deletedEventIds = [];

        do {
            $result = $this->apiClient->listEvents($user, $calendarId, $syncToken, $pageToken);
            $events = $result['events'];
            $pageToken = $result['nextPageToken'];

            foreach ($events as $googleEvent) {
                if ('cancelled' === $googleEvent->getStatus()) {
                    $eventId = $this->processGoogleEvent($googleEvent, $agenda);
                    if (null !== $eventId) {
                        $deletedEventIds[] = $eventId;
                    }
                } else {
                    $eventId = $this->processGoogleEvent($googleEvent, $agenda);
                    if (null !== $eventId) {
                        $changedEventIds[] = $eventId;
                    }
                }
            }

            $this->entityManager->flush();
        } while (null !== $pageToken);

        // Store new sync token
        if (null !== $result['nextSyncToken']) {
            $agenda->setGoogleSyncToken($result['nextSyncToken']);
        }
        $agenda->setLastGoogleSyncAt(new \DateTimeImmutable());
        $this->entityManager->flush();

        // Publish Mercure updates and index in Elasticsearch
        $userId = (string) $user->getId();
        foreach ($changedEventIds as $eventId) {
            $this->publishMercureUpdate($eventId, $userId);
            $this->messageBus->dispatch(new IndexDocumentCommand(
                entityClass: Event::class,
                entityId: $eventId,
            ));
        }
        foreach ($deletedEventIds as $eventId) {
            $this->publishMercureUpdate($eventId, $userId);
            $this->messageBus->dispatch(new DeleteDocumentCommand(
                indexName: 'events',
                documentId: $eventId,
            ));
        }
    }

    private function processGoogleEvent(
        \Google\Service\Calendar\Event $googleEvent,
        Agenda $agenda,
    ): ?string {
        $googleEventId = $googleEvent->getId();
        $existing = $this->eventRepository->findByGoogleEventId($googleEventId, $agenda);

        // Handle cancelled events
        if ('cancelled' === $googleEvent->getStatus()) {
            if (null !== $existing) {
                $eventId = (string) $existing->getId();
                $this->entityManager->remove($existing);

                return $eventId;
            }

            return null;
        }

        // Skip if Google hasn't changed since our last sync
        if (null !== $existing) {
            /** @var ?string $googleUpdated */
            $googleUpdated = $googleEvent->getUpdated();
            $localUpdated = $existing->getGoogleUpdatedAt();
            if (null !== $localUpdated && null !== $googleUpdated) {
                if (new \DateTimeImmutable($googleUpdated) <= $localUpdated) {
                    return (string) $existing->getId();
                }
            }
        }

        // Create or update
        $event = $this->eventMapper->fromGoogle($googleEvent, $agenda, $existing);

        if (null === $existing) {
            $this->entityManager->persist($event);
        }

        return (string) $event->getId();
    }

    private function publishMercureUpdate(string $eventId, string $userId): void
    {
        try {
            $event = $this->eventRepository->find($eventId);
            $iri = '/api/events/'.$eventId;
            $scopedTopic = '/users/'.$userId.$iri;

            if (null === $event) {
                // Event was deleted
                $this->hub->publish(new Update(
                    topics: [$scopedTopic],
                    data: json_encode(['@id' => $iri, 'deleted' => true], JSON_THROW_ON_ERROR),
                    private: true,
                ));

                return;
            }

            $this->hub->publish(new Update(
                topics: [$scopedTopic],
                data: json_encode(['@id' => $iri] + $event->toMercurePayload(), JSON_THROW_ON_ERROR),
                private: true,
            ));
        } catch (\Throwable $e) {
            $this->logger->error('Failed to publish Mercure update: {error}', [
                'error' => $e->getMessage(),
                'eventId' => $eventId,
            ]);
        }
    }
}
