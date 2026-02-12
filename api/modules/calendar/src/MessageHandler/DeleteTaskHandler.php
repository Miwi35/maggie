<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\DeleteTaskCommand;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\UseCase\DeleteTask;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteTaskHandler
{
    public function __construct(
        private readonly DeleteTask $deleteTask,
        private readonly TaskRepository $taskRepository,
    ) {
    }

    public function __invoke(DeleteTaskCommand $command): void
    {
        $task = $this->taskRepository->find($command->taskId);
        if ($task === null) {
            throw new \DomainException("Task not found: {$command->taskId}");
        }

        $this->deleteTask->execute($task);
    }
}
