<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\DeleteTaskFromGoogleCommand;
use Maggie\Calendar\Service\GoogleTasksApiClient;
use Maggie\Core\Repository\UserRepository;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteTaskFromGoogleHandler
{
    public function __construct(
        private readonly GoogleTasksApiClient $apiClient,
        private readonly UserRepository $userRepository,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function __invoke(DeleteTaskFromGoogleCommand $command): void
    {
        $user = $this->userRepository->find($command->userId);
        if (null === $user) {
            return;
        }

        try {
            $this->apiClient->deleteTask($user, $command->googleTaskListId, $command->googleTaskId);
        } catch (\Throwable $e) {
            $this->logger->error('Failed to delete task from Google: {error}', [
                'error' => $e->getMessage(),
                'googleTaskId' => $command->googleTaskId,
            ]);
        }
    }
}
