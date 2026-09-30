<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\PushTaskToGoogleCommand;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Service\GoogleTasksSyncService;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class PushTaskToGoogleHandler
{
    public function __construct(
        private readonly GoogleTasksSyncService $syncService,
        private readonly TaskRepository $taskRepository,
    ) {
    }

    public function __invoke(PushTaskToGoogleCommand $command): void
    {
        $task = $this->taskRepository->find($command->taskId);
        if (null === $task) {
            return;
        }

        $this->syncService->pushTaskToGoogle($task, $command->changedFields);
    }
}
