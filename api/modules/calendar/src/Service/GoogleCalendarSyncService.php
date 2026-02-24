<?php

namespace Maggie\Calendar\Service;

use Doctrine\ORM\EntityManagerInterface;
use Google\Service\Exception as GoogleServiceException;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\EventStatus;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Core\Entity\User;
use Psr\Log\LoggerInterface;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;

class GoogleCalendarSyncService
{
    public function __construct(
        private readonly GoogleCalendarApiClient $apiClient,
        private readonly GoogleEventMapper $eventMapper,
        private readonly EventRepository $eventRepository,
        private readonly AgendaRepository $agendaRepository,
        private readonly EntityManagerInterface $entityManager,
        private readonly HubInterface $hub,
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

        try {
            $this->doPull($user, $agenda, $calendarId, $syncToken);
        } catch (GoogleServiceException $e) {
            if ($e->getCode() === 410) {
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
     * @param string[]|null $changedFields Fields that changed (null = full update)
     */
    public function pushEventToGoogle(Event $event, string $action, ?array $changedFields = null): void
    {
        $agenda = $event->getAgenda();
        if (!$agenda->isGoogleSynced()) {
            return;
        }

        $user = $agenda->getUser();
        $calendarId = $agenda->getGoogleCalendarId();

        try {
            $googleEventId = $event->getGoogleEventId();
            if ($action === 'create' || $googleEventId === null) {
                $googleEvent = $this->eventMapper->toGoogle($event);
                $result = $this->apiClient->insertEvent($user, $calendarId, $googleEvent);
                $event->setGoogleEventId($result->getId());
                $event->setGoogleEtag($result->getEtag());
                /** @var ?string $updatedAt */
                $updatedAt = $result->getUpdated();
                if ($updatedAt) {
                    $event->setGoogleUpdatedAt(new \DateTimeImmutable($updatedAt));
                }
            } elseif ($changedFields !== null && $changedFields !== []) {
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

    public function deleteEventFromGoogle(Agenda $agenda, string $googleEventId): void
    {
        if (!$agenda->isGoogleSynced()) {
            return;
        }

        try {
            $this->apiClient->deleteEvent($agenda->getUser(), $agenda->getGoogleCalendarId(), $googleEventId);
        } catch (GoogleServiceException $e) {
            if ($e->getCode() === 404 || $e->getCode() === 410) {
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

    private function doPull(User $user, Agenda $agenda, string $calendarId, ?string $syncToken): void
    {
        $pageToken = null;
        $changedEventIds = [];

        do {
            $result = $this->apiClient->listEvents($user, $calendarId, $syncToken, $pageToken);
            $events = $result['events'];
            $pageToken = $result['nextPageToken'];

            foreach ($events as $googleEvent) {
                $eventId = $this->processGoogleEvent($googleEvent, $agenda);
                if ($eventId !== null) {
                    $changedEventIds[] = $eventId;
                }
            }

            $this->entityManager->flush();
        } while ($pageToken !== null);

        // Store new sync token
        if ($result['nextSyncToken'] !== null) {
            $agenda->setGoogleSyncToken($result['nextSyncToken']);
        }
        $agenda->setLastGoogleSyncAt(new \DateTimeImmutable());
        $this->entityManager->flush();

        // Publish Mercure updates for changed events
        foreach ($changedEventIds as $eventId) {
            $this->publishMercureUpdate($eventId);
        }
    }

    private function processGoogleEvent(
        \Google\Service\Calendar\Event $googleEvent,
        Agenda $agenda,
    ): ?string {
        $googleEventId = $googleEvent->getId();
        $existing = $this->eventRepository->findByGoogleEventId($googleEventId, $agenda);

        // Handle cancelled events
        if ($googleEvent->getStatus() === 'cancelled') {
            if ($existing !== null) {
                $eventId = (string) $existing->getId();
                $this->entityManager->remove($existing);
                return $eventId;
            }
            return null;
        }

        // Skip if Google hasn't changed since our last sync
        if ($existing !== null) {
            /** @var ?string $googleUpdated */
            $googleUpdated = $googleEvent->getUpdated();
            $localUpdated = $existing->getGoogleUpdatedAt();
            if ($localUpdated !== null && $googleUpdated !== null) {
                if (new \DateTimeImmutable($googleUpdated) <= $localUpdated) {
                    return (string) $existing->getId();
                }
            }
        }

        // Create or update
        $event = $this->eventMapper->fromGoogle($googleEvent, $agenda, $existing);

        if ($existing === null) {
            $this->entityManager->persist($event);
        }

        return (string) $event->getId();
    }

    private function publishMercureUpdate(string $eventId): void
    {
        try {
            $event = $this->eventRepository->find($eventId);
            if ($event === null) {
                // Event was deleted
                $iri = '/api/events/' . $eventId;
                $this->hub->publish(new Update(
                    topics: [$iri],
                    data: json_encode(['@id' => $iri, 'deleted' => true], JSON_THROW_ON_ERROR),
                ));
                return;
            }

            $iri = '/api/events/' . $event->getId();
            $this->hub->publish(new Update(
                topics: [$iri],
                data: json_encode(['@id' => $iri] + $event->toMercurePayload(), JSON_THROW_ON_ERROR),
            ));
        } catch (\Throwable $e) {
            $this->logger->error('Failed to publish Mercure update: {error}', [
                'error' => $e->getMessage(),
                'eventId' => $eventId,
            ]);
        }
    }
}
