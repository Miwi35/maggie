<?php

namespace Maggie\Calendar\Service;

use Doctrine\ORM\EntityManagerInterface;
use Google\Service\Exception as GoogleServiceException;
use Google\Service\Tasks\Task as GoogleTask;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\MercureTopic;
use Psr\Log\LoggerInterface;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;
use Symfony\Component\Messenger\MessageBusInterface;

class GoogleTasksSyncService
{
    public function __construct(
        private readonly GoogleTasksApiClient $apiClient,
        private readonly GoogleTaskMapper $taskMapper,
        private readonly TaskRepository $taskRepository,
        private readonly GoogleTaskListSelection $selection,
        private readonly EntityManagerInterface $entityManager,
        private readonly HubInterface $hub,
        private readonly MessageBusInterface $messageBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function pullFromGoogle(User $user): void
    {
        if (!$user->hasGoogleCalendarTokens()) {
            return;
        }

        // The list is the owner's choice, made on the settings screen. It used
        // to be whichever list Google returned first, which is what MAG-118
        // reported: an account with several lists silently synced one of them.
        $taskListId = $user->getGoogleTaskListId();
        if (null === $taskListId) {
            return;
        }

        try {
            $googleTasks = $this->apiClient->listTasks($user, $taskListId);
        } catch (GoogleServiceException $e) {
            // The list was deleted on Google, or shared and then revoked. The
            // choice is dropped so the screen asks for a new one, rather than
            // every sync failing on a list that is never coming back.
            if (404 === $e->getCode() || 410 === $e->getCode()) {
                $this->logger->warning('The chosen Google Tasks list is gone, forgetting it: {taskListId}', [
                    'taskListId' => $taskListId,
                    'userId' => (string) $user->getId(),
                ]);
                $this->selection->forget($user);

                return;
            }

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
        /** @var array<string, Task> $mapped the tasks read this pull, by Google ID */
        $mapped = [];
        /** @var array<string, ?string> $googleParents their parent's Google ID */
        $googleParents = [];
        $changedTaskIds = [];
        $removedTaskIds = [];

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
                    $removedTaskIds[] = (string) $existing->getId();
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
            $mapped[$googleTaskId] = $task;
            /** @var ?string $googleParent */
            $googleParent = $googleTask->getParent();
            $googleParents[$googleTaskId] = $googleParent;

            if (null === $existing) {
                $this->entityManager->persist($task);
            }

            $changedTaskIds[] = (string) $task->getId();
        }

        // The parents, once every task of the list is known: Google lists a
        // subtask before its parent as readily as after.
        foreach ($mapped as $googleTaskId => $task) {
            $googleParent = $googleParents[$googleTaskId];
            $task->setParent(null === $googleParent ? null : ($mapped[$googleParent] ?? $existingByGoogleId[$googleParent] ?? null));
        }

        // Remove local tasks whose Google IDs are no longer present
        foreach ($existingByGoogleId as $googleTaskId => $task) {
            if (!isset($seenGoogleIds[$googleTaskId])) {
                $changedTaskIds[] = (string) $task->getId();
                $removedTaskIds[] = (string) $task->getId();
                $this->entityManager->remove($task);
            }
        }

        $this->entityManager->flush();

        foreach ($removedTaskIds as $taskId) {
            $this->messageBus->dispatch(new DeleteDocumentCommand(indexName: 'tasks', documentId: $taskId));
        }

        // Publish Mercure updates
        foreach ($changedTaskIds as $taskId) {
            $this->publishMercureUpdate($taskId, (string) $user->getId());
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
            // A subtask goes under its parent: Google must know the parent first.
            $parent = $task->getParent();
            if (null !== $parent && null === $parent->getGoogleTaskId()) {
                $this->pushTaskToGoogle($parent);
            }

            if (null === $googleTaskId) {
                // Create new task on Google, in its place among its siblings
                $googleTask = $this->taskMapper->toGoogle($task);
                $result = $this->orFirstWhenPreviousIsGone(
                    $task,
                    fn (?string $previous): GoogleTask => $this->apiClient->insertTask(
                        $user,
                        $taskListId,
                        $googleTask,
                        $task->getParent()?->getGoogleTaskId(),
                        $previous,
                    ),
                );
                $task->setGoogleTaskId($result->getId());
                $task->setGoogleTaskListId($taskListId);
                $this->takePlacement($task, $result);
                $this->takeVersion($task, $result);
            } elseif (null !== $changedFields && [] !== $changedFields) {
                // A new parent or a new place: Google's move, which no patch can do
                if (\in_array('position', $changedFields, true)) {
                    $result = $this->orFirstWhenPreviousIsGone(
                        $task,
                        fn (?string $previous): GoogleTask => $this->apiClient->moveTask(
                            $user,
                            $taskListId,
                            $googleTaskId,
                            $task->getParent()?->getGoogleTaskId(),
                            $previous,
                        ),
                    );
                    $this->takePlacement($task, $result);
                    $this->takeVersion($task, $result);
                    $changedFields = array_values(array_diff($changedFields, ['position']));
                }

                // Partial update via PATCH
                if ([] !== $changedFields) {
                    $googleTask = $this->taskMapper->toGooglePatch($task, $changedFields);
                    $result = $this->apiClient->patchTask($user, $taskListId, $googleTaskId, $googleTask);
                    $this->takeVersion($task, $result);
                }
            } else {
                // Full update via PUT
                $googleTask = $this->taskMapper->toGoogle($task);
                $result = $this->apiClient->updateTask($user, $taskListId, $googleTaskId, $googleTask);
                $this->takeVersion($task, $result);
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

    /**
     * Runs a placing call with the previous sibling Maggie knows; when Google
     * answers 404 « Previous task id not found » — that sibling was deleted
     * there and the pull has not said so yet (MAG-678) — places the task first
     * instead. The next pull brings Google's order back.
     *
     * @param \Closure(?string): GoogleTask $place
     */
    private function orFirstWhenPreviousIsGone(Task $task, \Closure $place): GoogleTask
    {
        $previous = $this->previousSiblingOnGoogle($task);

        try {
            return $place($previous);
        } catch (GoogleServiceException $e) {
            if (null === $previous || 404 !== $e->getCode()) {
                throw $e;
            }

            $this->logger->warning('Previous sibling not found on Google, placing the task first: {googleTaskId}', [
                'googleTaskId' => $previous,
                'taskId' => (string) $task->getId(),
            ]);

            return $place(null);
        }
    }

    /**
     * The Google ID of the sibling $task comes after in Maggie's order, for
     * Google's `previous`; null puts it first. A sibling Google does not know
     * yet is passed over.
     */
    private function previousSiblingOnGoogle(Task $task): ?string
    {
        $previous = null;
        foreach ($this->taskRepository->findSiblings($task->getUser(), $task->getParent()) as $sibling) {
            if ($sibling === $task || $sibling->getId()->equals($task->getId())) {
                return $previous;
            }
            $previous = $sibling->getGoogleTaskId() ?? $previous;
        }

        return $previous;
    }

    /**
     * Google's position replaces Maggie's: the siblings read from Google hold
     * Google's, and one scheme is what keeps the order comparable.
     */
    private function takePlacement(Task $task, GoogleTask $result): void
    {
        /** @var ?string $position */
        $position = $result->getPosition();
        if (null !== $position) {
            $task->setPosition($position);
        }
    }

    private function takeVersion(Task $task, GoogleTask $result): void
    {
        $task->setGoogleTaskEtag($result->getEtag());
        /** @var ?string $updatedAt */
        $updatedAt = $result->getUpdated();
        if ($updatedAt) {
            $task->setGoogleTaskUpdatedAt(new \DateTimeImmutable($updatedAt));
        }
    }

    private function publishMercureUpdate(string $taskId, string $userId): void
    {
        try {
            $task = $this->taskRepository->find($taskId);
            $iri = MercureTopic::item('/api/tasks', $taskId);
            $scopedTopic = MercureTopic::scoped($userId, $iri);

            if (null === $task) {
                // Task was deleted
                $this->hub->publish(new Update(
                    topics: [$scopedTopic],
                    data: json_encode(['@id' => $iri, 'deleted' => true], JSON_THROW_ON_ERROR),
                    private: true,
                ));

                return;
            }

            $this->hub->publish(new Update(
                topics: [$scopedTopic],
                data: json_encode(['@id' => $iri] + $task->toMercurePayload(), JSON_THROW_ON_ERROR),
                private: true,
            ));
        } catch (\Throwable $e) {
            $this->logger->error('Failed to publish Mercure update: {error}', [
                'error' => $e->getMessage(),
                'taskId' => $taskId,
            ]);
        }
    }
}
