<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskCriticality;
use Maggie\Calendar\Enum\TaskPriority;
use Maggie\Calendar\Message\PushTaskToGoogleCommand;
use Maggie\Calendar\Message\UpdateTaskCommand;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\UseCase\UpdateTask;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\TransportNamesStamp;

#[AsMessageHandler]
class UpdateTaskHandler
{
    public function __construct(
        private readonly UpdateTask $updateTask,
        private readonly TaskRepository $taskRepository,
        private readonly MessageBusInterface $bus,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function __invoke(UpdateTaskCommand $command): Task
    {
        $task = $this->taskRepository->find($command->taskId);
        if ($task === null) {
            throw new \DomainException("Task not found: {$command->taskId}");
        }

        if ($command->title !== null) {
            $task->setTitle($command->title);
        }
        if ($command->description !== null) {
            $task->setDescription($command->description);
        } elseif ($command->clears('description')) {
            $task->setDescription(null);
        }
        if ($command->priority !== null) {
            $task->setPriority(TaskPriority::from($command->priority));
        }
        if ($command->criticality !== null) {
            $task->setCriticality(TaskCriticality::from($command->criticality));
        }
        if ($command->dueDate !== null) {
            $task->setDueDate($command->dueDate);
        } elseif ($command->clears('dueDate')) {
            $task->setDueDate(null);
        }
        if ($command->completedAt !== null) {
            $task->setCompletedAt($command->completedAt);
        } elseif ($command->clears('completedAt')) {
            $task->setCompletedAt(null);
        }

        // Track which fields were explicitly set in the command
        $changedFields = [];
        if ($command->title !== null) {
            $changedFields[] = 'title';
        }
        if ($command->description !== null || $command->clears('description')) {
            $changedFields[] = 'description';
        }
        if ($command->dueDate !== null || $command->clears('dueDate')) {
            $changedFields[] = 'dueDate';
        }
        if ($command->completedAt !== null || $command->clears('completedAt')) {
            $changedFields[] = 'completedAt';
        }

        $task = $this->updateTask->execute($task);

        if ($task->isGoogleSynced()) {
            $pushCommand = new PushTaskToGoogleCommand(
                taskId: (string) $task->getId(),
                changedFields: $changedFields ?: null,
            );
            try {
                $this->bus->dispatch($pushCommand);
            } catch (\Throwable $e) {
                $this->logger->warning('Google sync failed, queuing retry: {error}', ['error' => $e->getMessage()]);
                $this->bus->dispatch($pushCommand, [new TransportNamesStamp(['async'])]);
            }
        }

        return $task;
    }
}
