<?php

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Message\DeleteTaskCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Task, void> */
class DeleteTaskProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteTaskCommand(
            taskId: (string) $data->getId(),
        ));
    }
}
