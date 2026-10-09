<?php

namespace Maggie\Calendar\Service;

use Doctrine\ORM\EntityManagerInterface;
use Google\Service\Tasks\TaskList;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\MercureTopic;
use Psr\Log\LoggerInterface;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;

/**
 * The Google Tasks list the owner syncs with — their choice, not the first one
 * Google returns (MAG-118).
 *
 * A Google account can hold several lists, and the sync used to adopt whichever
 * came back first; the settings screen that was supposed to offer the choice
 * called three endpoints nobody had written. The choice lives here now, shared
 * by the endpoints that make it and by the sync that has to drop it when Google
 * no longer has the list.
 *
 * Nothing syncs until the choice is made: adopting a list on the owner's behalf
 * is what this ticket exists to undo, and a single-list account is not a licence
 * to guess — the screen shows the one list and the owner connects it.
 */
class GoogleTaskListSelection
{
    public function __construct(
        private readonly GoogleTasksApiClient $apiClient,
        private readonly TaskRepository $taskRepository,
        private readonly EntityManagerInterface $entityManager,
        private readonly HubInterface $hub,
        private readonly LoggerInterface $logger,
    ) {
    }

    /**
     * The lists the account holds, in the shape the settings screen reads.
     *
     * @return list<array{id: string, title: string}>
     */
    public function available(User $user): array
    {
        return array_values(array_map(
            fn (TaskList $list) => ['id' => (string) $list->getId(), 'title' => (string) $list->getTitle()],
            $this->apiClient->listTaskLists($user),
        ));
    }

    /** The title Google gives a list, or null when the account no longer holds it. */
    public function titleOf(User $user, string $taskListId): ?string
    {
        foreach ($this->available($user) as $list) {
            if ($list['id'] === $taskListId) {
                return $list['title'];
            }
        }

        return null;
    }

    public function choose(User $user, string $taskListId): void
    {
        if ($taskListId === $user->getGoogleTaskListId()) {
            return;
        }

        $this->detachTasksOfOtherLists($user, $taskListId);
        $user->setGoogleTaskListId($taskListId);
        $this->entityManager->flush();
        $this->publish($user);
    }

    /**
     * Lets go of the tasks the previous list owned.
     *
     * They stay in Maggie as plain local tasks. Keeping their Google
     * identifiers would have the next push update a task in the new list by an
     * id that list has never issued — and a pull of the new list would never
     * meet them, so nothing would ever clean them up.
     */
    private function detachTasksOfOtherLists(User $user, string $taskListId): void
    {
        $stale = $this->taskRepository->findTrackedOnAnotherGoogleList($user, $taskListId);
        if ([] === $stale) {
            return;
        }

        foreach ($stale as $task) {
            $task->setGoogleTaskId(null)
                ->setGoogleTaskListId(null)
                ->setGoogleTaskEtag(null)
                ->setGoogleTaskUpdatedAt(null);
        }

        $this->logger->info('{count} tasks detached from the previous Google Tasks list', [
            'count' => \count($stale),
            'userId' => (string) $user->getId(),
            'taskListId' => $taskListId,
        ]);
    }

    /**
     * Stops syncing, keeping what Google gave the local tasks.
     *
     * Reconnecting the same list then resumes on the same rows; clearing the
     * identifiers here would make the next pull create a second copy of every
     * task. Switching to another list clears them instead, at {@see choose()}.
     */
    public function forget(User $user): void
    {
        if (null === $user->getGoogleTaskListId()) {
            return;
        }

        $user->setGoogleTaskListId(null);
        $this->entityManager->flush();
        $this->publish($user);
    }

    /**
     * Tells the open screens the choice moved.
     *
     * Published here rather than through the bus: the write is a field on the
     * user, not one of the CRUD commands ProjectionMiddleware projects.
     */
    private function publish(User $user): void
    {
        $userId = (string) $user->getId();
        $topic = MercureTopic::item(MercureTopic::collection(User::class), $userId);

        try {
            $this->hub->publish(new Update(
                topics: [MercureTopic::scoped($userId, $topic)],
                data: json_encode(
                    ['@id' => $topic] + $user->toMercurePayload(['googleTaskListId']),
                    JSON_THROW_ON_ERROR,
                ),
                private: true,
            ));
        } catch (\Throwable $e) {
            // The choice is saved; an update nobody receives is not worth
            // failing the request that made it.
            $this->logger->error('Failed to publish the Google Tasks list choice: {error}', [
                'error' => $e->getMessage(),
                'userId' => $userId,
            ]);
        }
    }
}
