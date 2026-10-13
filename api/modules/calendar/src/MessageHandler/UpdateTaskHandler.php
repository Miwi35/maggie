<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskCriticality;
use Maggie\Calendar\Enum\TaskPriority;
use Maggie\Calendar\Message\PushTaskToGoogleCommand;
use Maggie\Calendar\Message\UpdateTaskCommand;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Service\TaskHierarchy;
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
        private readonly TaskHierarchy $hierarchy,
        private readonly TaskRepository $taskRepository,
        private readonly MessageBusInterface $bus,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function __invoke(UpdateTaskCommand $command): Task
    {
        $task = $this->taskRepository->find($command->taskId);
        if (null === $task) {
            throw new \DomainException("Task not found: {$command->taskId}");
        }

        if (null !== $command->title) {
            $task->setTitle($command->title);
        }
        if (null !== $command->description) {
            $task->setDescription($command->description);
        } elseif ($command->clears('description')) {
            $task->setDescription(null);
        }
        if (null !== $command->priority) {
            $task->setPriority(TaskPriority::from($command->priority));
        }
        if (null !== $command->criticality) {
            $task->setCriticality(TaskCriticality::from($command->criticality));
        }
        if (null !== $command->dueDate) {
            $task->setDueDate($command->dueDate);
        } elseif ($command->clears('dueDate')) {
            $task->setDueDate(null);
        }
        if (null !== $command->completedAt) {
            $task->setCompletedAt($command->completedAt);
        } elseif ($command->clears('completedAt')) {
            $task->setCompletedAt(null);
        }

        // A new parent, or none: the task goes last among its new siblings
        // unless the command says where.
        $parentMoved = false;
        if (null !== $command->parentId) {
            $parent = $this->hierarchy->parentFor($task, $task->getUser(), $command->parentId);
            $parentMoved = $parent !== $task->getParent();
            $task->setParent($parent);
        } elseif ($command->clears('parent') && null !== $task->getParent()) {
            $parentMoved = true;
            $task->setParent(null);
        }
        if (null !== $command->position) {
            $task->setPosition($command->position);
        } elseif ($parentMoved) {
            $task->setPosition($this->hierarchy->lastPosition($task->getUser(), $task->getParent(), $task));
        }

        // Track which fields were explicitly set in the command
        $changedFields = [];
        if (null !== $command->title) {
            $changedFields[] = 'title';
        }
        if (null !== $command->description || $command->clears('description')) {
            $changedFields[] = 'description';
        }
        if (null !== $command->dueDate || $command->clears('dueDate')) {
            $changedFields[] = 'dueDate';
        }
        if (null !== $command->completedAt || $command->clears('completedAt')) {
            $changedFields[] = 'completedAt';
        }
        if ($parentMoved || null !== $command->position) {
            // Google moves a task with its own call, not with a patch.
            $changedFields[] = 'position';
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
