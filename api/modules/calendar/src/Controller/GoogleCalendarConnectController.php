<?php

namespace Maggie\Calendar\Controller;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Message\CreateAgendaCommand;
use Maggie\Calendar\Message\PullFromGoogleCommand;
use Maggie\Calendar\Message\PushEventToGoogleCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;

final class GoogleCalendarConnectController
{
    public function __construct(
        private readonly GoogleCalendarApiClient $apiClient,
        private readonly AgendaRepository $agendaRepository,
        private readonly EntityManagerInterface $entityManager,
        private readonly MessageBusInterface $messageBus,
        private readonly Security $security,
        private readonly string $googleWebhookUrl,
        private readonly string $googleWebhookToken,
    ) {
    }

    #[Route('/api/calendar/google/calendars', name: 'google_calendar_list', methods: ['GET'])]
    public function listCalendars(): JsonResponse
    {
        /** @var User $user */
        $user = $this->security->getUser();

        if (!$user->hasGoogleCalendarTokens()) {
            return new JsonResponse(
                ['error' => 'Google Calendar not authorized. Please connect your Google account first.'],
                Response::HTTP_FORBIDDEN,
            );
        }

        $calendars = $this->apiClient->listCalendars($user);
        $result = array_map(fn ($cal) => [
            'id' => $cal->getId(),
            'summary' => $cal->getSummary(),
            'description' => $cal->getDescription(),
            'primary' => $cal->getPrimary() ?: false,
            'backgroundColor' => $cal->getBackgroundColor(),
        ], $calendars);

        return new JsonResponse($result);
    }

    #[Route('/api/calendar/google/import', name: 'google_calendar_import', methods: ['POST'])]
    public function import(Request $request): JsonResponse
    {
        /** @var User $user */
        $user = $this->security->getUser();

        if (!$user->hasGoogleCalendarTokens()) {
            return new JsonResponse(
                ['error' => 'Google Calendar not authorized.'],
                Response::HTTP_FORBIDDEN,
            );
        }

        $data = json_decode($request->getContent(), true);
        $googleCalendarId = $data['googleCalendarId'] ?? null;

        if (!$googleCalendarId) {
            return new JsonResponse(
                ['error' => 'googleCalendarId is required.'],
                Response::HTTP_BAD_REQUEST,
            );
        }

        // Find the Google Calendar details
        $calendars = $this->apiClient->listCalendars($user);
        $googleCal = null;
        foreach ($calendars as $cal) {
            if ($cal->getId() === $googleCalendarId) {
                $googleCal = $cal;
                break;
            }
        }

        if (null === $googleCal) {
            return new JsonResponse(
                ['error' => 'Google Calendar not found.'],
                Response::HTTP_NOT_FOUND,
            );
        }

        $name = $data['name'] ?? $googleCal->getSummary();
        $color = $data['color'] ?? $googleCal->getBackgroundColor();

        // Create agenda via CQRS (MercurePublishMiddleware will publish automatically)
        $envelope = $this->messageBus->dispatch(new CreateAgendaCommand(
            userId: (string) $user->getId(),
            name: $name,
            color: $color,
        ));

        $agenda = $envelope->last(HandledStamp::class)?->getResult();
        if (null === $agenda) {
            return new JsonResponse(
                ['error' => 'Failed to create agenda.'],
                Response::HTTP_INTERNAL_SERVER_ERROR,
            );
        }

        // Link to Google Calendar
        $agenda->setGoogleCalendarId($googleCalendarId);
        $this->entityManager->flush();

        // Set up webhook
        if ($this->googleWebhookUrl) {
            try {
                $watchResult = $this->apiClient->watchEvents(
                    $user,
                    $googleCalendarId,
                    $this->googleWebhookUrl,
                    $this->googleWebhookToken,
                );
                $agenda->setGoogleWatchChannelId($watchResult['channelId']);
                $agenda->setGoogleWatchResourceId($watchResult['resourceId']);
                $agenda->setGoogleWatchExpiresAt(
                    (new \DateTimeImmutable())->setTimestamp((int) ($watchResult['expiration'] / 1000))
                );
                $this->entityManager->flush();
            } catch (\Throwable) {
                // Webhook setup is non-critical
            }
        }

        // Dispatch initial sync
        $this->messageBus->dispatch(new PullFromGoogleCommand(agendaId: (string) $agenda->getId()));

        return new JsonResponse([
            'id' => (string) $agenda->getId(),
            'name' => $agenda->getName(),
            'color' => $agenda->getColor(),
            'googleCalendarId' => $agenda->getGoogleCalendarId(),
        ], Response::HTTP_CREATED);
    }

    #[Route('/api/calendar/google/export', name: 'google_calendar_export', methods: ['POST'])]
    public function export(Request $request): JsonResponse
    {
        /** @var User $user */
        $user = $this->security->getUser();

        if (!$user->hasGoogleCalendarTokens()) {
            return new JsonResponse(
                ['error' => 'Google Calendar not authorized.'],
                Response::HTTP_FORBIDDEN,
            );
        }

        $data = json_decode($request->getContent(), true);
        $agendaId = $data['agendaId'] ?? null;

        if (!$agendaId) {
            return new JsonResponse(
                ['error' => 'agendaId is required.'],
                Response::HTTP_BAD_REQUEST,
            );
        }

        $agenda = $this->agendaRepository->find($agendaId);
        if (null === $agenda || $agenda->getUser() !== $user) {
            return new JsonResponse(['error' => 'Agenda not found.'], Response::HTTP_NOT_FOUND);
        }

        if (null !== $agenda->getGoogleCalendarId()) {
            return new JsonResponse(
                ['error' => 'Agenda is already synced with Google Calendar.'],
                Response::HTTP_CONFLICT,
            );
        }

        // Create new Google Calendar
        $googleCalendar = $this->apiClient->insertCalendar($user, $agenda->getName(), $agenda->getDescription());
        $agenda->setGoogleCalendarId($googleCalendar->getId());
        $this->entityManager->flush();

        // Set up webhook
        if ($this->googleWebhookUrl) {
            try {
                $watchResult = $this->apiClient->watchEvents(
                    $user,
                    $googleCalendar->getId(),
                    $this->googleWebhookUrl,
                    $this->googleWebhookToken,
                );
                $agenda->setGoogleWatchChannelId($watchResult['channelId']);
                $agenda->setGoogleWatchResourceId($watchResult['resourceId']);
                $agenda->setGoogleWatchExpiresAt(
                    (new \DateTimeImmutable())->setTimestamp((int) ($watchResult['expiration'] / 1000))
                );
                $this->entityManager->flush();
            } catch (\Throwable) {
                // Webhook setup is non-critical
            }
        }

        // Push all existing events to Google
        foreach ($agenda->getEvents() as $event) {
            $this->messageBus->dispatch(new PushEventToGoogleCommand(
                eventId: (string) $event->getId(),
                action: 'create',
            ));
        }

        return new JsonResponse([
            'id' => (string) $agenda->getId(),
            'name' => $agenda->getName(),
            'color' => $agenda->getColor(),
            'googleCalendarId' => $agenda->getGoogleCalendarId(),
        ], Response::HTTP_CREATED);
    }
}
