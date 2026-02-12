<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Entity\TaskCriticality;
use Maggie\Calendar\Entity\TaskPriority;
use Maggie\Calendar\Message\CreateTaskCommand;
use Maggie\Calendar\UseCase\CreateTask;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateTaskHandler
{
    public function __construct(
        private readonly CreateTask $createTask,
    ) {
    }

    public function __invoke(CreateTaskCommand $command): Task
    {
        $task = new Task();
        $task->setName($command->name);

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

        return $this->createTask->execute($task);
    }
}
