<?php

namespace Maggie\Calendar\Service;

use Doctrine\ORM\EntityManagerInterface;
use Google\Client as GoogleClient;
use Google\Service\Tasks as GoogleTasksService;
use Google\Service\Tasks\Task as GoogleTask;
use Google\Service\Tasks\TaskList;
use Maggie\Core\Entity\User;

class GoogleTasksApiClient
{
    public function __construct(
        private readonly EntityManagerInterface $entityManager,
        private readonly string $googleClientId,
        private readonly string $googleClientSecret,
        /**
         * Empty everywhere but the e2e stack, where it points at the WireMock
         * stand-in so a journey's Google sync never leaves the Docker network.
         * Empty means the library's own root — dev and prod are unchanged.
         */
        private readonly string $googleApiBaseUrl = '',
    ) {
    }

    public function getTasksService(User $user): GoogleTasksService
    {
        $client = $this->buildClient($user);

        return new GoogleTasksService($client, $this->googleApiBaseUrl !== '' ? $this->googleApiBaseUrl : null);
    }

    /**
     * @return TaskList[]
     */
    public function listTaskLists(User $user): array
    {
        $service = $this->getTasksService($user);
        $taskLists = $service->tasklists->listTasklists();

        return $taskLists->getItems();
    }

    /**
     * @return GoogleTask[]
     */
    public function listTasks(User $user, string $taskListId): array
    {
        $service = $this->getTasksService($user);
        $allTasks = [];
        $pageToken = null;

        do {
            $params = [
                'maxResults' => 100,
                'showCompleted' => true,
                'showHidden' => true,
            ];

            if ($pageToken !== null) {
                $params['pageToken'] = $pageToken;
            }

            $result = $service->tasks->listTasks($taskListId, $params);
            $items = $result->getItems();
            if ($items !== null) {
                $allTasks = array_merge($allTasks, $items);
            }
            $pageToken = $result->getNextPageToken();
        } while ($pageToken !== null);

        return $allTasks;
    }

    public function insertTask(User $user, string $taskListId, GoogleTask $task): GoogleTask
    {
        $service = $this->getTasksService($user);

        return $service->tasks->insert($taskListId, $task);
    }

    public function updateTask(User $user, string $taskListId, string $taskId, GoogleTask $task): GoogleTask
    {
        $service = $this->getTasksService($user);

        return $service->tasks->update($taskListId, $taskId, $task);
    }

    public function patchTask(User $user, string $taskListId, string $taskId, GoogleTask $task): GoogleTask
    {
        $service = $this->getTasksService($user);

        return $service->tasks->patch($taskListId, $taskId, $task);
    }

    public function deleteTask(User $user, string $taskListId, string $taskId): void
    {
        $service = $this->getTasksService($user);
        $service->tasks->delete($taskListId, $taskId);
    }

    private function buildClient(User $user): GoogleClient
    {
        $client = new GoogleClient();
        $client->setClientId($this->googleClientId);
        $client->setClientSecret($this->googleClientSecret);
        $client->setAccessType('offline');

        $client->setAccessToken([
            'access_token' => $user->getGoogleAccessToken(),
            'refresh_token' => $user->getGoogleRefreshToken(),
            'expires_in' => $user->getGoogleTokenExpiresAt()
                ? $user->getGoogleTokenExpiresAt()->getTimestamp() - time()
                : 0,
            'created' => $user->getGoogleTokenExpiresAt()
                ? $user->getGoogleTokenExpiresAt()->getTimestamp() - 3600
                : time(),
        ]);

        if ($client->isAccessTokenExpired() && $user->getGoogleRefreshToken()) {
            $client->fetchAccessTokenWithRefreshToken($user->getGoogleRefreshToken());
            $newToken = $client->getAccessToken();

            $user->setGoogleAccessToken($newToken['access_token']);
            if (isset($newToken['expires_in'])) {
                $user->setGoogleTokenExpiresAt(
                    new \DateTimeImmutable('+' . $newToken['expires_in'] . ' seconds')
                );
            }
            if (isset($newToken['refresh_token'])) {
                $user->setGoogleRefreshToken($newToken['refresh_token']);
            }

            $this->entityManager->flush();
        }

        return $client;
    }
}
