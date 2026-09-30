<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\DeleteTaskCommand;
use Maggie\Calendar\Message\DeleteTaskFromGoogleCommand;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\UseCase\DeleteTask;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\TransportNamesStamp;

#[AsMessageHandler]
class DeleteTaskHandler
{
    public function __construct(
        private readonly DeleteTask $deleteTask,
        private readonly TaskRepository $taskRepository,
        private readonly MessageBusInterface $bus,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function __invoke(DeleteTaskCommand $command): void
    {
        $task = $this->taskRepository->find($command->taskId);
        if (null === $task) {
            throw new \DomainException("Task not found: {$command->taskId}");
        }

        if ($task->isGoogleSynced()) {
            $deleteCommand = new DeleteTaskFromGoogleCommand(
                taskId: (string) $task->getId(),
                googleTaskId: $task->getGoogleTaskId(),
                googleTaskListId: $task->getGoogleTaskListId(),
                userId: (string) $task->getUser()->getId(),
            );
            try {
                $this->bus->dispatch($deleteCommand);
            } catch (\Throwable $e) {
                $this->logger->warning('Google sync failed, queuing retry: {error}', ['error' => $e->getMessage()]);
                $this->bus->dispatch($deleteCommand, [new TransportNamesStamp(['async'])]);
            }
        }

        $this->deleteTask->execute($task);
    }
}
