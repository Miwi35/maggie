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
        if ($taskListId === null) {
            return;
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
            if ($googleTaskId !== null) {
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
            $task = $this->taskMapper->fromGoogle($googleTask, $user, $existing);
            $task->setGoogleTaskListId($taskListId);

            if ($existing === null) {
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

    public function pushTaskToGoogle(Task $task): void
    {
        $user = $task->getUser();
        if (!$user->hasGoogleCalendarTokens()) {
            return;
        }

        $taskListId = $user->getGoogleTaskListId();
        if ($taskListId === null) {
            return;
        }

        $googleTask = $this->taskMapper->toGoogle($task);

        try {
            $googleTaskId = $task->getGoogleTaskId();
            if ($googleTaskId === null) {
                // Create new task on Google
                $result = $this->apiClient->insertTask($user, $taskListId, $googleTask);
                $task->setGoogleTaskId($result->getId());
                $task->setGoogleTaskListId($taskListId);
                $task->setGoogleTaskEtag($result->getEtag());
                /** @var ?string $updatedAt */
                $updatedAt = $result->getUpdated();
                if ($updatedAt) {
                    $task->setGoogleTaskUpdatedAt(new \DateTimeImmutable($updatedAt));
                }
            } else {
                // Update existing task on Google
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

        if ($taskListId === null || $googleTaskId === null) {
            return;
        }

        try {
            $this->apiClient->deleteTask($user, $taskListId, $googleTaskId);
        } catch (GoogleServiceException $e) {
            if ($e->getCode() === 404 || $e->getCode() === 410) {
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
            if ($task === null) {
                // Task was deleted
                $iri = '/api/tasks/' . $taskId;
                $this->hub->publish(new Update(
                    topics: [$iri],
                    data: json_encode(['@id' => $iri, 'deleted' => true], JSON_THROW_ON_ERROR),
                ));
                return;
            }

            $iri = '/api/tasks/' . $task->getId();
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
