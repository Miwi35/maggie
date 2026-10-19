<?php

declare(strict_types=1);

namespace Maggie\Calendar\Controller;

use Maggie\Calendar\Entity\TaskReminder;
use Maggie\Calendar\Message\AnswerTaskReminderCommand;
use Maggie\Calendar\Repository\TaskReminderRepository;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The buttons of a task reminder (MAG-510), pressed on the phone's
 * notification or in the admin's bell: « Je m'y mets », « Dans 10 min »,
 * « Ce soir », « Fait », « Demain ». Another user's reminder is not found; a
 * reminder answers once.
 */
final class TaskReminderController
{
    public function __construct(
        private readonly Security $security,
        private readonly TaskReminderRepository $repository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/task-reminders/{id}/action', name: 'api_task_reminder_action', methods: ['POST'])]
    public function act(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        try {
            $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            $body = null;
        }
        $action = \is_array($body) ? ($body['action'] ?? null) : null;
        if (!\is_string($action) || !\in_array($action, TaskReminder::ACTIONS, true)) {
            return new JsonResponse(['error' => 'action must be one of: '.implode(', ', TaskReminder::ACTIONS)], Response::HTTP_BAD_REQUEST);
        }

        $reminder = $this->repository->findOwned($user, $id);
        if (null === $reminder) {
            return new JsonResponse(['error' => 'Reminder not found.'], Response::HTTP_NOT_FOUND);
        }

        try {
            $answer = $this->bus->dispatch(new AnswerTaskReminderCommand((string) $user->getId(), (string) $reminder->getId(), $action))
                ->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            // A refusal (answered already, tonight passed, tomorrow full) is the caller's; anything else is ours.
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_CONFLICT);
        }

        return new JsonResponse($answer);
    }
}
