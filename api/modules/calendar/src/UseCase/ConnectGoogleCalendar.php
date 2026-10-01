<?php

namespace Maggie\Calendar\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Google\Service\Calendar\CalendarListEntry;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\CreateAgendaCommand;
use Maggie\Calendar\Message\PullFromGoogleCommand;
use Maggie\Calendar\Message\UpdateAgendaCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Maggie\Calendar\Service\GoogleCalendarNameMapper;
use Maggie\Core\Entity\User;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Connects a Google calendar to an agenda, once (MAG-148).
 *
 * Connecting the same calendar twice used to create a second agenda, and both
 * copies then synced on their own and duplicated every event. One Google
 * calendar is one agenda: a second connection finds the first one, refreshes
 * what Google owns, and syncs it again.
 */
class ConnectGoogleCalendar
{
    public function __construct(
        private readonly AgendaRepository $agendaRepository,
        private readonly GoogleCalendarApiClient $apiClient,
        private readonly GoogleCalendarNameMapper $nameMapper,
        private readonly EntityManagerInterface $entityManager,
        private readonly MessageBusInterface $messageBus,
        private readonly LoggerInterface $logger,
        private readonly string $googleWebhookUrl,
        private readonly string $googleWebhookToken,
    ) {
    }

    /**
     * @return array{agenda: Agenda, created: bool} `created` tells the caller
     *                                              whether anything was added — a reconnection is a success, not a conflict
     */
    public function execute(User $user, CalendarListEntry $entry): array
    {
        $existing = $this->agendaRepository->findOneByGoogleCalendarId($user, (string) $entry->getId());

        if (null === $existing) {
            $agenda = $this->create($user, $entry);
        } else {
            $agenda = $existing;
            $this->refresh($agenda, $entry);
        }

        $this->ensureWatchChannel($user, $agenda);
        $this->messageBus->dispatch(new PullFromGoogleCommand(agendaId: (string) $agenda->getId()));

        return ['agenda' => $agenda, 'created' => null === $existing];
    }

    private function create(User $user, CalendarListEntry $entry): Agenda
    {
        $envelope = $this->messageBus->dispatch(new CreateAgendaCommand(
            userId: (string) $user->getId(),
            name: $this->nameMapper->nameFor($entry),
            color: $entry->getBackgroundColor(),
            // The Google primary calendar is where an appointment belongs when
            // nothing else says otherwise — but only until the user picks one
            // themselves, and their choice is never overwritten (MAG-149).
            isDefault: true === $entry->getPrimary() && null === $this->agendaRepository->findDefault($user),
            googleCalendarId: (string) $entry->getId(),
        ));

        $agenda = $envelope->last(HandledStamp::class)?->getResult();
        if (!$agenda instanceof Agenda) {
            throw new \RuntimeException('Failed to create the agenda for Google calendar '.$entry->getId());
        }

        return $agenda;
    }

    /**
     * Brings back what Google owns — the displayed name and the colour — so the
     * two applications show the same agenda. Goes through the bus so the admin
     * and the mobile app see the change and the search index follows.
     */
    private function refresh(Agenda $agenda, CalendarListEntry $entry): void
    {
        $name = $this->nameMapper->nameFor($entry);
        /** @var ?string $color a shared calendar may carry no colour */
        $color = $entry->getBackgroundColor();

        if ($name === $agenda->getName() && (null === $color || $color === $agenda->getColor())) {
            return;
        }

        $this->messageBus->dispatch(new UpdateAgendaCommand(
            agendaId: (string) $agenda->getId(),
            name: $name,
            color: $color,
        ));
    }

    /**
     * Google stops pushing changes when the channel expires, and asking for a
     * new one on every reconnection would leave dead channels behind, so an
     * existing channel with time left is kept as is.
     */
    private function ensureWatchChannel(User $user, Agenda $agenda): void
    {
        if ('' === $this->googleWebhookUrl) {
            return;
        }

        $expiresAt = $agenda->getGoogleWatchExpiresAt();
        $channelId = $agenda->getGoogleWatchChannelId();
        $resourceId = $agenda->getGoogleWatchResourceId();

        if (null !== $channelId && null !== $expiresAt && $expiresAt > new \DateTimeImmutable('+1 hour')) {
            return;
        }

        // The channel being replaced is closed first. Google would otherwise
        // keep pushing on it until it expired, and those pushes would arrive
        // with a channel id no agenda answers to any more.
        if (null !== $channelId && null !== $resourceId) {
            try {
                $this->apiClient->stopWatch($user, $channelId, $resourceId);
            } catch (\Throwable $e) {
                $this->logger->warning('Failed to stop the previous Google watch channel: {error}', [
                    'error' => $e->getMessage(),
                    'agenda' => (string) $agenda->getId(),
                ]);
            }
        }

        try {
            $watch = $this->apiClient->watchEvents(
                $user,
                (string) $agenda->getGoogleCalendarId(),
                $this->googleWebhookUrl,
                $this->googleWebhookToken,
            );

            $agenda->setGoogleWatchChannelId($watch['channelId']);
            $agenda->setGoogleWatchResourceId($watch['resourceId']);
            $agenda->setGoogleWatchExpiresAt(
                (new \DateTimeImmutable())->setTimestamp(intdiv($watch['expiration'], 1000))
            );
            $this->entityManager->flush();
        } catch (\Throwable $e) {
            // Without a channel the agenda still syncs every five minutes
            // through the cron, so this is not worth failing the connection.
            $this->logger->warning('Failed to register the Google watch channel: {error}', [
                'error' => $e->getMessage(),
                'agenda' => (string) $agenda->getId(),
            ]);
        }
    }
}
