<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Entity\TaskCriticality;
use Maggie\Calendar\Entity\TaskPriority;
use Maggie\Calendar\Message\CreateTaskCommand;
use Maggie\Calendar\Message\PushTaskToGoogleCommand;
use Maggie\Calendar\UseCase\CreateTask;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class CreateTaskHandler
{
    public function __construct(
        private readonly CreateTask $createTask,
        private readonly UserRepository $userRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(CreateTaskCommand $command): Task
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $task = new Task();
        $task->setUser($user);
        $task->setTitle($command->title);

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
        if ($command->completedAt !== null) {
            $task->setCompletedAt($command->completedAt);
        }

        $task = $this->createTask->execute($task);

        if ($user->getGoogleTaskListId() !== null) {
            $this->bus->dispatch(new PushTaskToGoogleCommand(taskId: (string) $task->getId()));
        }

        return $task;
    }
}
