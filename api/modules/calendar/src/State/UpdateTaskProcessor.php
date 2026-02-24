<?php

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Message\UpdateTaskCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Task, Task> */
class UpdateTaskProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Task
    {
        /** @var Task|null $previous */
        $previous = $context['previous_data'] ?? null;

        // Only send fields that actually changed compared to previous state
        $envelope = $this->bus->dispatch(new UpdateTaskCommand(
            taskId: (string) $data->getId(),
            title: $previous === null || $data->getTitle() !== $previous->getTitle() ? $data->getTitle() : null,
            description: $previous === null || $data->getDescription() !== $previous->getDescription() ? $data->getDescription() : null,
            priority: $previous === null || $data->getPriority() !== $previous->getPriority() ? $data->getPriority()->value : null,
            criticality: $previous === null || $data->getCriticality() !== $previous->getCriticality() ? $data->getCriticality()->value : null,
            dueDate: $previous === null || $data->getDueDate() != $previous->getDueDate() ? $data->getDueDate() : null,
            completedAt: $previous === null || $data->getCompletedAt() != $previous->getCompletedAt() ? $data->getCompletedAt() : null,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
