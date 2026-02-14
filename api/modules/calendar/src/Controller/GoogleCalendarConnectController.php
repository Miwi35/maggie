<?php

namespace Maggie\Calendar\Controller;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Message\PullFromGoogleCommand;
use Maggie\Calendar\Message\PullTasksFromGoogleCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Maggie\Calendar\Service\GoogleTasksApiClient;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

final class GoogleCalendarConnectController
{
    public function __construct(
        private readonly GoogleCalendarApiClient $apiClient,
        private readonly GoogleTasksApiClient $tasksApiClient,
        private readonly AgendaRepository $agendaRepository,
        private readonly EventRepository $eventRepository,
        private readonly TaskRepository $taskRepository,
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
        $result = array_map(fn($cal) => [
            'id' => $cal->getId(),
            'summary' => $cal->getSummary(),
            'description' => $cal->getDescription(),
            'primary' => $cal->getPrimary() ?: false,
            'backgroundColor' => $cal->getBackgroundColor(),
        ], $calendars);

        return new JsonResponse($result);
    }

    #[Route('/api/calendar/google/connect', name: 'google_calendar_connect', methods: ['POST'])]
    public function connect(Request $request): JsonResponse
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
        $googleCalendarId = $data['googleCalendarId'] ?? null;

        if (!$agendaId || !$googleCalendarId) {
            return new JsonResponse(
                ['error' => 'agendaId and googleCalendarId are required.'],
                Response::HTTP_BAD_REQUEST,
            );
        }

        $agenda = $this->agendaRepository->find($agendaId);
        if ($agenda === null || $agenda->getUser() !== $user) {
            return new JsonResponse(['error' => 'Agenda not found.'], Response::HTTP_NOT_FOUND);
        }

        $agenda->setGoogleCalendarId($googleCalendarId);
        $this->entityManager->flush();

        // Set up Google push notifications (webhook)
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
                // Webhook setup is non-critical, cron will handle sync as fallback
            }
        }

        // Dispatch initial pull
        $this->messageBus->dispatch(new PullFromGoogleCommand(agendaId: $agendaId));

        return new JsonResponse(['status' => 'connected'], Response::HTTP_ACCEPTED);
    }

    #[Route('/api/calendar/google/disconnect', name: 'google_calendar_disconnect', methods: ['POST'])]
    public function disconnect(Request $request): JsonResponse
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $data = json_decode($request->getContent(), true);
        $agendaId = $data['agendaId'] ?? null;

        if (!$agendaId) {
            return new JsonResponse(['error' => 'agendaId is required.'], Response::HTTP_BAD_REQUEST);
        }

        $agenda = $this->agendaRepository->find($agendaId);
        if ($agenda === null || $agenda->getUser() !== $user) {
            return new JsonResponse(['error' => 'Agenda not found.'], Response::HTTP_NOT_FOUND);
        }

        // Stop watch channel if active
        if ($agenda->getGoogleWatchChannelId() !== null && $agenda->getGoogleWatchResourceId() !== null) {
            try {
                $this->apiClient->stopWatch(
                    $user,
                    $agenda->getGoogleWatchChannelId(),
                    $agenda->getGoogleWatchResourceId(),
                );
            } catch (\Throwable) {
                // Best effort
            }
        }

        // Clear Google fields on agenda
        $agenda->setGoogleCalendarId(null);
        $agenda->setGoogleSyncToken(null);
        $agenda->setLastGoogleSyncAt(null);
        $agenda->setGoogleWatchChannelId(null);
        $agenda->setGoogleWatchResourceId(null);
        $agenda->setGoogleWatchExpiresAt(null);

        // Clear Google fields on all events in this agenda
        $events = $this->eventRepository->findGoogleSyncedByAgenda($agenda);
        foreach ($events as $event) {
            $event->setGoogleEventId(null);
            $event->setGoogleEtag(null);
            $event->setGoogleUpdatedAt(null);
        }

        $this->entityManager->flush();

        return new JsonResponse(['status' => 'disconnected']);
    }

    #[Route('/api/calendar/google/task-lists', name: 'google_task_lists', methods: ['GET'])]
    public function listTaskLists(): JsonResponse
    {
        /** @var User $user */
        $user = $this->security->getUser();

        if (!$user->hasGoogleCalendarTokens()) {
            return new JsonResponse(
                ['error' => 'Google not authorized.'],
                Response::HTTP_FORBIDDEN,
            );
        }

        $taskLists = $this->tasksApiClient->listTaskLists($user);
        $result = array_map(fn($tl) => [
            'id' => $tl->getId(),
            'title' => $tl->getTitle(),
        ], $taskLists);

        return new JsonResponse($result);
    }

    #[Route('/api/calendar/google/connect-tasks', name: 'google_tasks_connect', methods: ['POST'])]
    public function connectTasks(Request $request): JsonResponse
    {
        /** @var User $user */
        $user = $this->security->getUser();

        if (!$user->hasGoogleCalendarTokens()) {
            return new JsonResponse(
                ['error' => 'Google not authorized.'],
                Response::HTTP_FORBIDDEN,
            );
        }

        $data = json_decode($request->getContent(), true);
        $googleTaskListId = $data['googleTaskListId'] ?? null;

        if (!$googleTaskListId) {
            return new JsonResponse(
                ['error' => 'googleTaskListId is required.'],
                Response::HTTP_BAD_REQUEST,
            );
        }

        $user->setGoogleTaskListId($googleTaskListId);
        $this->entityManager->flush();

        // Dispatch initial pull
        $this->messageBus->dispatch(new PullTasksFromGoogleCommand(userId: (string) $user->getId()));

        return new JsonResponse(['status' => 'connected'], Response::HTTP_ACCEPTED);
    }

    #[Route('/api/calendar/google/disconnect-tasks', name: 'google_tasks_disconnect', methods: ['POST'])]
    public function disconnectTasks(): JsonResponse
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $user->setGoogleTaskListId(null);

        // Clear Google fields on all tasks for this user
        $tasks = $this->taskRepository->findBy(['user' => $user]);
        foreach ($tasks as $task) {
            $task->setGoogleTaskId(null);
            $task->setGoogleTaskListId(null);
            $task->setGoogleTaskEtag(null);
            $task->setGoogleTaskUpdatedAt(null);
        }

        $this->entityManager->flush();

        return new JsonResponse(['status' => 'disconnected']);
    }
}
