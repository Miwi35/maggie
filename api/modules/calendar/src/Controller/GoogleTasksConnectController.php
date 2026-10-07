<?php

namespace Maggie\Calendar\Controller;

use Maggie\Calendar\Message\PullTasksFromGoogleCommand;
use Maggie\Calendar\Service\GoogleTaskListSelection;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The Google Tasks list the owner syncs with (MAG-118).
 *
 * The settings screen has been calling these three routes since it was written;
 * none of them existed, so the choice it offered went nowhere and the sync took
 * the first list the account returned.
 */
final class GoogleTasksConnectController
{
    public function __construct(
        private readonly GoogleTaskListSelection $selection,
        private readonly MessageBusInterface $messageBus,
        private readonly Security $security,
    ) {
    }

    #[Route('/api/calendar/google/task-lists', name: 'google_task_lists', methods: ['GET'])]
    public function listTaskLists(): JsonResponse
    {
        /** @var User $user */
        $user = $this->security->getUser();

        if (!$user->hasGoogleCalendarTokens()) {
            return new JsonResponse(
                ['error' => 'Google Tasks not authorized. Please connect your Google account first.'],
                Response::HTTP_FORBIDDEN,
            );
        }

        return new JsonResponse($this->selection->available($user));
    }

    #[Route('/api/calendar/google/connect-tasks', name: 'google_tasks_connect', methods: ['POST'])]
    public function connectTasks(Request $request): JsonResponse
    {
        /** @var User $user */
        $user = $this->security->getUser();

        if (!$user->hasGoogleCalendarTokens()) {
            return new JsonResponse(
                ['error' => 'Google Tasks not authorized.'],
                Response::HTTP_FORBIDDEN,
            );
        }

        $data = json_decode($request->getContent(), true);
        $googleTaskListId = \is_array($data) ? ($data['googleTaskListId'] ?? null) : null;

        if (!\is_string($googleTaskListId) || '' === $googleTaskListId) {
            return new JsonResponse(
                ['error' => 'googleTaskListId is required.'],
                Response::HTTP_BAD_REQUEST,
            );
        }

        // Checked against the account rather than taken on trust: a list the
        // owner cannot see would be stored, and every sync would then fail on a
        // 404 nobody sees.
        $title = $this->selection->titleOf($user, $googleTaskListId);
        if (null === $title) {
            return new JsonResponse(
                ['error' => 'Google Tasks list not found.'],
                Response::HTTP_NOT_FOUND,
            );
        }

        $this->selection->choose($user, $googleTaskListId);

        // The list the owner just chose is worth pulling now, not at the next
        // turn of the cron.
        $this->messageBus->dispatch(new PullTasksFromGoogleCommand(userId: (string) $user->getId()));

        return new JsonResponse([
            'googleTaskListId' => $googleTaskListId,
            'title' => $title,
        ]);
    }

    /**
     * Disconnecting asks Google nothing, so it answers even to an account whose
     * authorization is gone — which is one of the reasons to disconnect.
     */
    #[Route('/api/calendar/google/disconnect-tasks', name: 'google_tasks_disconnect', methods: ['POST'])]
    public function disconnectTasks(): JsonResponse
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $this->selection->forget($user);

        return new JsonResponse(['googleTaskListId' => null]);
    }
}
