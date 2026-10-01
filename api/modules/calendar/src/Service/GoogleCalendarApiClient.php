<?php

namespace Maggie\Calendar\Service;

use Doctrine\ORM\EntityManagerInterface;
use Google\Client as GoogleClient;
use Google\Service\Calendar as GoogleCalendarService;
use Google\Service\Calendar\CalendarListEntry;
use Google\Service\Calendar\Channel;
use Google\Service\Calendar\Event as GoogleEvent;
use Google\Service\Calendar\Events as GoogleEvents;
use Maggie\Core\Entity\User;

class GoogleCalendarApiClient
{
    public function __construct(
        private readonly EntityManagerInterface $entityManager,
        private readonly string $googleClientId,
        private readonly string $googleClientSecret,
        /**
         * Empty everywhere but the e2e stack, where it points at the WireMock
         * stand-in so a journey's Google sync never leaves the Docker network.
         * Empty means the library's own root — dev and prod are unchanged.
         */
        private readonly string $googleApiBaseUrl = '',
        private readonly string $kernelEnvironment = '',
    ) {
    }

    /**
     * Google pushes to whatever address it is given and never complains, so a
     * placeholder left in the configuration (MAG-193) silently turns push
     * notifications into a five-minute cron. In prod the address must be a
     * real https one.
     */
    public static function assertUsableWebhookUrl(string $webhookUrl, string $environment): void
    {
        if ('prod' !== $environment) {
            return;
        }

        $scheme = parse_url($webhookUrl, \PHP_URL_SCHEME);
        $host = parse_url($webhookUrl, \PHP_URL_HOST);

        if ('https' !== $scheme || !\is_string($host) || preg_match('/(^|\.)example\.(com|org|net)$/i', $host)) {
            throw new \InvalidArgumentException(\sprintf('GOOGLE_WEBHOOK_URL "%s" cannot receive Google push notifications in prod: it must be a real https address, not an example.com placeholder.', $webhookUrl));
        }
    }

    public function getCalendarService(User $user): GoogleCalendarService
    {
        $client = $this->buildClient($user);

        return new GoogleCalendarService($client, '' !== $this->googleApiBaseUrl ? $this->googleApiBaseUrl : null);
    }

    /**
     * @return CalendarListEntry[]
     */
    public function listCalendars(User $user): array
    {
        $service = $this->getCalendarService($user);
        $calendarList = $service->calendarList->listCalendarList();

        return $calendarList->getItems();
    }

    public function getCalendarListEntry(User $user, string $calendarId): CalendarListEntry
    {
        $service = $this->getCalendarService($user);

        return $service->calendarList->get($calendarId);
    }

    /**
     * @return array{events: GoogleEvent[], nextSyncToken: string|null, nextPageToken: string|null}
     */
    public function listEvents(
        User $user,
        string $calendarId,
        ?string $syncToken = null,
        ?string $pageToken = null,
    ): array {
        $service = $this->getCalendarService($user);
        $params = [
            'maxResults' => 250,
            'singleEvents' => false,
        ];

        if (null !== $syncToken) {
            $params['syncToken'] = $syncToken;
        } else {
            $params['timeMin'] = (new \DateTimeImmutable('-1 year'))->format(\DateTimeInterface::RFC3339);
        }

        if (null !== $pageToken) {
            $params['pageToken'] = $pageToken;
        }

        /** @var GoogleEvents $result */
        $result = $service->events->listEvents($calendarId, $params);

        return [
            'events' => $result->getItems(),
            'nextSyncToken' => $result->getNextSyncToken(),
            'nextPageToken' => $result->getNextPageToken(),
        ];
    }

    public function getEvent(User $user, string $calendarId, string $eventId): GoogleEvent
    {
        $service = $this->getCalendarService($user);

        return $service->events->get($calendarId, $eventId);
    }

    public function insertEvent(User $user, string $calendarId, GoogleEvent $event): GoogleEvent
    {
        $service = $this->getCalendarService($user);

        return $service->events->insert($calendarId, $event);
    }

    public function updateEvent(User $user, string $calendarId, string $eventId, GoogleEvent $event): GoogleEvent
    {
        $service = $this->getCalendarService($user);

        return $service->events->update($calendarId, $eventId, $event);
    }

    public function patchEvent(User $user, string $calendarId, string $eventId, GoogleEvent $event): GoogleEvent
    {
        $service = $this->getCalendarService($user);

        return $service->events->patch($calendarId, $eventId, $event);
    }

    public function moveEvent(User $user, string $fromCalendarId, string $eventId, string $toCalendarId): GoogleEvent
    {
        $service = $this->getCalendarService($user);

        return $service->events->move($fromCalendarId, $eventId, $toCalendarId);
    }

    public function deleteEvent(User $user, string $calendarId, string $eventId): void
    {
        $service = $this->getCalendarService($user);
        $service->events->delete($calendarId, $eventId);
    }

    /**
     * @return array{channelId: string, resourceId: string, expiration: int}
     */
    public function watchEvents(User $user, string $calendarId, string $webhookUrl, string $token): array
    {
        self::assertUsableWebhookUrl($webhookUrl, $this->kernelEnvironment);

        $service = $this->getCalendarService($user);

        $channel = new Channel();
        $channelId = bin2hex(random_bytes(16));
        $channel->setId($channelId);
        $channel->setType('web_hook');
        $channel->setAddress($webhookUrl);
        $channel->setToken($token);

        $result = $service->events->watch($calendarId, $channel);

        return [
            'channelId' => $result->getId(),
            'resourceId' => $result->getResourceId(),
            'expiration' => (int) $result->getExpiration(),
        ];
    }

    public function insertCalendar(User $user, string $name, ?string $description = null): GoogleCalendarService\Calendar
    {
        $service = $this->getCalendarService($user);
        $calendar = new GoogleCalendarService\Calendar();
        $calendar->setSummary($name);
        if ($description) {
            $calendar->setDescription($description);
        }

        return $service->calendars->insert($calendar);
    }

    public function deleteCalendar(User $user, string $calendarId): void
    {
        $service = $this->getCalendarService($user);
        $service->calendars->delete($calendarId);
    }

    public function stopWatch(User $user, string $channelId, string $resourceId): void
    {
        $service = $this->getCalendarService($user);

        $channel = new Channel();
        $channel->setId($channelId);
        $channel->setResourceId($resourceId);

        $service->channels->stop($channel);
    }

    private function buildClient(User $user): GoogleClient
    {
        $client = new GoogleClient();
        $client->setClientId($this->googleClientId);
        $client->setClientSecret($this->googleClientSecret);
        $client->setAccessType('offline');

        $client->setAccessToken([
            'access_token' => $user->getGoogleAccessToken(),
            'refresh_token' => $user->getGoogleRefreshToken(),
            'expires_in' => $user->getGoogleTokenExpiresAt()
                ? $user->getGoogleTokenExpiresAt()->getTimestamp() - time()
                : 0,
            'created' => $user->getGoogleTokenExpiresAt()
                ? $user->getGoogleTokenExpiresAt()->getTimestamp() - 3600
                : time(),
        ]);

        if ($client->isAccessTokenExpired() && $user->getGoogleRefreshToken()) {
            $client->fetchAccessTokenWithRefreshToken($user->getGoogleRefreshToken());
            $newToken = $client->getAccessToken();

            $user->setGoogleAccessToken($newToken['access_token']);
            if (isset($newToken['expires_in'])) {
                $user->setGoogleTokenExpiresAt(
                    new \DateTimeImmutable('+'.$newToken['expires_in'].' seconds')
                );
            }
            if (isset($newToken['refresh_token'])) {
                $user->setGoogleRefreshToken($newToken['refresh_token']);
            }

            $this->entityManager->flush();
        }

        return $client;
    }
}
