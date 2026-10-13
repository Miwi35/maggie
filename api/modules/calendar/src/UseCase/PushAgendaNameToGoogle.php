<?php

namespace Maggie\Calendar\UseCase;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\UpdateAgendaCommand;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Sends a connected agenda's new name to Google (MAG-385).
 *
 * Google stays the source on the way in, so a name it never received is put back by the next sync
 * while Maggie has said « c'est fait ». When Google cannot take the name, the agenda goes back to the
 * one it had, which is Google's, and the door that asked for the rename is told.
 */
class PushAgendaNameToGoogle
{
    public function __construct(
        private readonly GoogleCalendarApiClient $apiClient,
        private readonly MessageBusInterface $bus,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function execute(Agenda $agenda, string $previousName): void
    {
        if (!$agenda->isGoogleSynced() || $agenda->getName() === $previousName) {
            return;
        }

        try {
            $this->send($agenda);

            return;
        } catch (\DomainException $e) {
            $reason = $e->getMessage();
        } catch (\Throwable $e) {
            $this->logger->warning('Failed to rename the Google calendar of agenda {agenda}: {error}', [
                'agenda' => (string) $agenda->getId(),
                'error' => $e->getMessage(),
                'exception' => $e,
            ]);
            $reason = 'Google could not be reached or did not allow it';
        }

        try {
            $this->bus->dispatch(new UpdateAgendaCommand(
                agendaId: (string) $agenda->getId(),
                name: $previousName,
                fromGoogle: true,
            ));
        } finally {
            $agenda->refuseRename(\sprintf('The agenda could not be renamed in Google (%s). It keeps its name « %s ».', $reason, $previousName));
        }
    }

    private function send(Agenda $agenda): void
    {
        $user = $agenda->getUser();
        $calendarId = (string) $agenda->getGoogleCalendarId();

        // The primary calendar's name is the account's own label, which Maggie shows as « Défaut »
        // whatever it is called: a new name would be sent, then replaced by the next sync.
        if (true === $this->apiClient->getCalendarListEntry($user, $calendarId)->getPrimary()) {
            throw new \DomainException('the main Google calendar always shows as « Défaut »');
        }

        $this->apiClient->renameCalendar($user, $calendarId, $agenda->getName());
    }
}
