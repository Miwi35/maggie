<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\DeleteTaskCommand;
use Maggie\Calendar\Message\DeleteTaskFromGoogleCommand;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\UseCase\DeleteTask;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class DeleteTaskHandler
{
    public function __construct(
        private readonly DeleteTask $deleteTask,
        private readonly TaskRepository $taskRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(DeleteTaskCommand $command): void
    {
        $task = $this->taskRepository->find($command->taskId);
        if ($task === null) {
            throw new \DomainException("Task not found: {$command->taskId}");
        }

        if ($task->isGoogleSynced()) {
            $this->bus->dispatch(new DeleteTaskFromGoogleCommand(
                taskId: (string) $task->getId(),
                googleTaskId: $task->getGoogleTaskId(),
                googleTaskListId: $task->getGoogleTaskListId(),
                userId: (string) $task->getUser()->getId(),
            ));
        }

        $this->deleteTask->execute($task);
    }
}
