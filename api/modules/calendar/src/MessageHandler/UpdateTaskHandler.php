<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Entity\TaskCriticality;
use Maggie\Calendar\Entity\TaskPriority;
use Maggie\Calendar\Message\UpdateTaskCommand;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\UseCase\UpdateTask;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateTaskHandler
{
    public function __construct(
        private readonly UpdateTask $updateTask,
        private readonly TaskRepository $taskRepository,
    ) {
    }

    public function __invoke(UpdateTaskCommand $command): Task
    {
        $task = $this->taskRepository->find($command->taskId);
        if ($task === null) {
            throw new \DomainException("Task not found: {$command->taskId}");
        }

        if ($command->name !== null) {
            $task->setName($command->name);
        }
        if ($command->description !== null) {
            $task->setDescription($command->description);
        }
        if ($command->priority !== null) {
            $task->setPriority(TaskPriority::from($command->priority));
        }
        if ($command->criticality !== null) {
            $task->setCriticality(TaskCriticality::from($command->criticality));
        }
        if ($command->dueDate !== null) {
            $task->setDueDate($command->dueDate);
        }
        if ($command->doneDate !== null) {
            $task->setDoneDate($command->doneDate);
        }

        return $this->updateTask->execute($task);
    }
}
