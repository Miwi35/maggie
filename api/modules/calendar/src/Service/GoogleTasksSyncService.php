<?php

namespace Maggie\Calendar\Service;

use Doctrine\ORM\EntityManagerInterface;
use Google\Service\Exception as GoogleServiceException;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Core\Entity\User;
use Psr\Log\LoggerInterface;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;

class GoogleTasksSyncService
{
    public function __construct(
        private readonly GoogleTasksApiClient $apiClient,
        private readonly GoogleTaskMapper $taskMapper,
        private readonly TaskRepository $taskRepository,
        private readonly EntityManagerInterface $entityManager,
        private readonly HubInterface $hub,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function pullFromGoogle(User $user): void
    {
        if (!$user->hasGoogleCalendarTokens()) {
            return;
        }

        $taskListId = $user->getGoogleTaskListId();
        if (null === $taskListId) {
            // Auto-detect: pick the first available task list
            try {
                $lists = $this->apiClient->listTaskLists($user);
                if ([] === $lists) {
                    return;
                }
                $taskListId = $lists[0]->getId();
                $user->setGoogleTaskListId($taskListId);
                $this->entityManager->flush();
            } catch (\Throwable $e) {
                $this->logger->error('Failed to auto-detect Google Task List: {error}', [
                    'error' => $e->getMessage(),
                    'userId' => (string) $user->getId(),
                ]);

                return;
            }
        }

        try {
            $googleTasks = $this->apiClient->listTasks($user, $taskListId);
        } catch (GoogleServiceException $e) {
            $this->logger->error('Failed to list Google Tasks: {error}', [
                'error' => $e->getMessage(),
                'userId' => (string) $user->getId(),
            ]);
            throw $e;
        }

        // Index existing local tasks by their Google Task ID
        $existingTasks = $this->taskRepository->findBy([
            'user' => $user,
            'googleTaskListId' => $taskListId,
        ]);
        $existingByGoogleId = [];
        foreach ($existingTasks as $task) {
            $googleTaskId = $task->getGoogleTaskId();
            if (null !== $googleTaskId) {
                $existingByGoogleId[$googleTaskId] = $task;
            }
        }

        // Track which Google IDs we've seen
        $seenGoogleIds = [];
        $changedTaskIds = [];

        foreach ($googleTasks as $googleTask) {
            $googleTaskId = $googleTask->getId();
            $seenGoogleIds[$googleTaskId] = true;

            // Handle deleted tasks
            /** @var ?bool $deleted */
            $deleted = $googleTask->getDeleted();
            if ($deleted) {
                if (isset($existingByGoogleId[$googleTaskId])) {
                    $existing = $existingByGoogleId[$googleTaskId];
                    $changedTaskIds[] = (string) $existing->getId();
                    $this->entityManager->remove($existing);
                }
                continue;
            }

            // Create or update
            $existing = $existingByGoogleId[$googleTaskId] ?? null;

            // Skip if Google hasn't changed since our last sync
            if (null !== $existing) {
                /** @var ?string $googleUpdated */
                $googleUpdated = $googleTask->getUpdated();
                $localUpdated = $existing->getGoogleTaskUpdatedAt();
                if (null !== $localUpdated && null !== $googleUpdated) {
                    if (new \DateTimeImmutable($googleUpdated) <= $localUpdated) {
                        continue;
                    }
                }
            }

            $task = $this->taskMapper->fromGoogle($googleTask, $user, $existing);
            $task->setGoogleTaskListId($taskListId);

            if (null === $existing) {
                $this->entityManager->persist($task);
            }

            $changedTaskIds[] = (string) $task->getId();
        }

        // Remove local tasks whose Google IDs are no longer present
        foreach ($existingByGoogleId as $googleTaskId => $task) {
            if (!isset($seenGoogleIds[$googleTaskId])) {
                $changedTaskIds[] = (string) $task->getId();
                $this->entityManager->remove($task);
            }
        }

        $this->entityManager->flush();

        // Publish Mercure updates
        foreach ($changedTaskIds as $taskId) {
            $this->publishMercureUpdate($taskId);
        }
    }

    /**
     * @param string[]|null $changedFields Fields that changed (null = full update)
     */
    public function pushTaskToGoogle(Task $task, ?array $changedFields = null): void
    {
        $user = $task->getUser();
        if (!$user->hasGoogleCalendarTokens()) {
            return;
        }

        $taskListId = $user->getGoogleTaskListId();
        if (null === $taskListId) {
            return;
        }

        try {
            $googleTaskId = $task->getGoogleTaskId();
            if (null === $googleTaskId) {
                // Create new task on Google
                $googleTask = $this->taskMapper->toGoogle($task);
                $result = $this->apiClient->insertTask($user, $taskListId, $googleTask);
                $task->setGoogleTaskId($result->getId());
                $task->setGoogleTaskListId($taskListId);
                $task->setGoogleTaskEtag($result->getEtag());
                /** @var ?string $updatedAt */
                $updatedAt = $result->getUpdated();
                if ($updatedAt) {
                    $task->setGoogleTaskUpdatedAt(new \DateTimeImmutable($updatedAt));
                }
            } elseif (null !== $changedFields && [] !== $changedFields) {
                // Partial update via PATCH
                $googleTask = $this->taskMapper->toGooglePatch($task, $changedFields);
                $result = $this->apiClient->patchTask($user, $taskListId, $googleTaskId, $googleTask);
                $task->setGoogleTaskEtag($result->getEtag());
                /** @var ?string $updatedAt */
                $updatedAt = $result->getUpdated();
                if ($updatedAt) {
                    $task->setGoogleTaskUpdatedAt(new \DateTimeImmutable($updatedAt));
                }
            } else {
                // Full update via PUT
                $googleTask = $this->taskMapper->toGoogle($task);
                $result = $this->apiClient->updateTask($user, $taskListId, $googleTaskId, $googleTask);
                $task->setGoogleTaskEtag($result->getEtag());
                /** @var ?string $updatedAt */
                $updatedAt = $result->getUpdated();
                if ($updatedAt) {
                    $task->setGoogleTaskUpdatedAt(new \DateTimeImmutable($updatedAt));
                }
            }

            $this->entityManager->flush();
        } catch (GoogleServiceException $e) {
            $this->logger->error('Failed to push task to Google: {error}', [
                'error' => $e->getMessage(),
                'taskId' => (string) $task->getId(),
            ]);
            throw $e;
        }
    }

    public function deleteTaskFromGoogle(Task $task): void
    {
        $user = $task->getUser();
        if (!$user->hasGoogleCalendarTokens()) {
            return;
        }

        $taskListId = $task->getGoogleTaskListId() ?? $user->getGoogleTaskListId();
        $googleTaskId = $task->getGoogleTaskId();

        if (null === $taskListId || null === $googleTaskId) {
            return;
        }

        try {
            $this->apiClient->deleteTask($user, $taskListId, $googleTaskId);
        } catch (GoogleServiceException $e) {
            if (404 === $e->getCode() || 410 === $e->getCode()) {
                // Already deleted on Google side, ignore
                $this->logger->info('Task already deleted on Google: {googleTaskId}', [
                    'googleTaskId' => $googleTaskId,
                ]);

                return;
            }
            throw $e;
        }
    }

    private function publishMercureUpdate(string $taskId): void
    {
        try {
            $task = $this->taskRepository->find($taskId);
            if (null === $task) {
                // Task was deleted
                $iri = '/api/tasks/'.$taskId;
                $this->hub->publish(new Update(
                    topics: [$iri],
                    data: json_encode(['@id' => $iri, 'deleted' => true], JSON_THROW_ON_ERROR),
                ));

                return;
            }

            $iri = '/api/tasks/'.$task->getId();
            $this->hub->publish(new Update(
                topics: [$iri],
                data: json_encode(['@id' => $iri] + $task->toMercurePayload(), JSON_THROW_ON_ERROR),
            ));
        } catch (\Throwable $e) {
            $this->logger->error('Failed to publish Mercure update: {error}', [
                'error' => $e->getMessage(),
                'taskId' => $taskId,
            ]);
        }
    }
}
