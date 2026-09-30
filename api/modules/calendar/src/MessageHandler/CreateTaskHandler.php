<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskCriticality;
use Maggie\Calendar\Enum\TaskPriority;
use Maggie\Calendar\Message\CreateTaskCommand;
use Maggie\Calendar\Message\PushTaskToGoogleCommand;
use Maggie\Calendar\UseCase\CreateTask;
use Maggie\Core\Repository\UserRepository;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\TransportNamesStamp;

#[AsMessageHandler]
class CreateTaskHandler
{
    public function __construct(
        private readonly CreateTask $createTask,
        private readonly UserRepository $userRepository,
        private readonly MessageBusInterface $bus,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function __invoke(CreateTaskCommand $command): Task
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $task = new Task();
        $task->setUser($user);
        $task->setTitle($command->title);

        if (null !== $command->description) {
            $task->setDescription($command->description);
        }
        if (null !== $command->priority) {
            $task->setPriority(TaskPriority::from($command->priority));
        }
        if (null !== $command->criticality) {
            $task->setCriticality(TaskCriticality::from($command->criticality));
        }
        if (null !== $command->dueDate) {
            $task->setDueDate($command->dueDate);
        }
        if (null !== $command->completedAt) {
            $task->setCompletedAt($command->completedAt);
        }

        $task = $this->createTask->execute($task);

        if (null !== $user->getGoogleTaskListId()) {
            $pushCommand = new PushTaskToGoogleCommand(taskId: (string) $task->getId());
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
