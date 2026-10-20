<?php

declare(strict_types=1);

namespace Maggie\Calendar\Controller;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Message\UpdateTaskSettingsCommand;
use Maggie\Calendar\Service\TaskOrderPreview;
use Maggie\Calendar\Service\TaskOrderWeights;
use Maggie\Calendar\UseCase\GetTaskSettings;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The task settings (MAG-406, MAG-407, MAG-500, MAG-508, MAG-510), created on first read. Written here and not
 * through an API Platform `Patch` on an identifier-less path, which would
 * build a fresh entity (MeOperationContractTest). The PUT applies the fields
 * it is sent, and in `orderWeights` the weights it is sent: the others keep
 * their value, and so do the urgencies and keys of `alertSchedule`.
 * `orderLevels` sets a group's weights by level, as the sliders of « Ce qui
 * passe en premier » do ({@see TaskOrderWeights::fromLevels()}).
 */
final class TaskSettingsController
{
    private const FIELDS = ['orderWeights', 'orderLevels', 'alertSchedule', 'morningTime', 'eveningTime', 'rewardAnimation', 'rewardVibration', 'rewardSound', 'tone', 'dayMoments', 'reminderDailyCap', 'quietStart', 'quietEnd'];

    public function __construct(
        private readonly Security $security,
        private readonly GetTaskSettings $getTaskSettings,
        private readonly MessageBusInterface $bus,
        private readonly TaskOrderPreview $preview,
    ) {
    }

    #[Route('/api/task-settings/me', name: 'api_task_settings_read', methods: ['GET'])]
    public function read(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        return new JsonResponse(GetTaskSettings::describe($this->getTaskSettings->forUser($user)));
    }

    /** Five real tasks in the order the saved weights give, for the sliders' preview. */
    #[Route('/api/task-settings/me/preview', name: 'api_task_settings_preview', methods: ['GET'])]
    public function preview(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        return new JsonResponse(['member' => array_map(static fn (Task $task): array => [
            'id' => (string) $task->getId(),
            'title' => $task->getTitle(),
            'dueDate' => $task->getDueDate()?->format('Y-m-d'),
            'urgency' => $task->getUrgency()?->value,
            'orderScore' => $task->getOrderScore(),
        ], $this->preview->for($user))]);
    }

    #[Route('/api/task-settings/me', name: 'api_task_settings_update', methods: ['PUT'])]
    public function update(Request $request): JsonResponse
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

        if (!\is_array($body) || [] === array_intersect_key($body, array_flip(self::FIELDS))) {
            return new JsonResponse(['error' => 'Provide at least one of: '.implode(', ', self::FIELDS)], Response::HTTP_BAD_REQUEST);
        }

        $unknown = array_diff(array_keys($body), self::FIELDS);
        if ([] !== $unknown) {
            return new JsonResponse(['error' => 'Unknown field(s): '.implode(', ', $unknown).'. Known fields: '.implode(', ', self::FIELDS)], Response::HTTP_BAD_REQUEST);
        }

        if (\array_key_exists('orderWeights', $body) && !\is_array($body['orderWeights'])) {
            return new JsonResponse(['error' => 'orderWeights must be an object of groups'], Response::HTTP_BAD_REQUEST);
        }

        if (\array_key_exists('alertSchedule', $body) && !\is_array($body['alertSchedule'])) {
            return new JsonResponse(['error' => 'alertSchedule must be an object of urgencies'], Response::HTTP_BAD_REQUEST);
        }

        if (\array_key_exists('morningTime', $body) && !\is_string($body['morningTime'])) {
            return new JsonResponse(['error' => 'morningTime must be a time "HH:MM"'], Response::HTTP_BAD_REQUEST);
        }

        if (\array_key_exists('eveningTime', $body) && !\is_string($body['eveningTime'])) {
            return new JsonResponse(['error' => 'eveningTime must be a time "HH:MM"'], Response::HTTP_BAD_REQUEST);
        }

        foreach (['quietStart', 'quietEnd'] as $field) {
            if (\array_key_exists($field, $body) && !\is_string($body[$field])) {
                return new JsonResponse(['error' => "{$field} must be a time \"HH:MM\""], Response::HTTP_BAD_REQUEST);
            }
        }

        if (\array_key_exists('dayMoments', $body) && !\is_array($body['dayMoments'])) {
            return new JsonResponse(['error' => 'dayMoments must be an object of moments'], Response::HTTP_BAD_REQUEST);
        }

        $orderWeights = $body['orderWeights'] ?? null;
        if (\array_key_exists('orderLevels', $body)) {
            try {
                // A group given a level takes the scaled weights, over any sent for it.
                $orderWeights = array_replace($orderWeights ?? [], TaskOrderWeights::fromLevels($body['orderLevels']));
            } catch (\InvalidArgumentException $e) {
                return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
            }
        }

        $settings = $this->getTaskSettings->forUser($user);

        try {
            $this->bus->dispatch(new UpdateTaskSettingsCommand(
                userId: (string) $user->getId(),
                taskSettingsId: (string) $settings->getId(),
                orderWeights: $orderWeights,
                alertSchedule: $body['alertSchedule'] ?? null,
                morningTime: $body['morningTime'] ?? null,
                eveningTime: $body['eveningTime'] ?? null,
                rewardAnimation: $body['rewardAnimation'] ?? null,
                rewardVibration: $body['rewardVibration'] ?? null,
                rewardSound: $body['rewardSound'] ?? null,
                tone: $body['tone'] ?? null,
                dayMoments: $body['dayMoments'] ?? null,
                reminderDailyCap: $body['reminderDailyCap'] ?? null,
                quietStart: $body['quietStart'] ?? null,
                quietEnd: $body['quietEnd'] ?? null,
            ));
        } catch (HandlerFailedException $e) {
            // A refused value is the caller's; anything else is ours, and not theirs to read.
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse(GetTaskSettings::describe($this->getTaskSettings->forUser($user)));
    }
}
