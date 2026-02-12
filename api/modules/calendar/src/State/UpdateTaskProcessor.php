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
        $envelope = $this->bus->dispatch(new UpdateTaskCommand(
            taskId: (string) $data->getId(),
            name: $data->getName(),
            description: $data->getDescription(),
            priority: $data->getPriority()->value,
            criticality: $data->getCriticality()->value,
            dueDate: $data->getDueDate(),
            doneDate: $data->getDoneDate(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
